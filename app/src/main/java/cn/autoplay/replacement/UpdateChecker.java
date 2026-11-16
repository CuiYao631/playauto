package cn.autoplay.replacement;

import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    /** Release 上传的安装包文件名，与构建脚本产物保持一致。 */
    static final String APK_ASSET_NAME = "AutoPlayReplacement-debug.apk";

    public static class UpdateInfo {
        public final String version;
        public final int versionCode;
        public final String url;
        public final String notes;

        UpdateInfo(String version, int versionCode, String url, String notes) {
            this.version = version;
            this.versionCode = versionCode;
            this.url = url;
            this.notes = notes;
        }
    }

    public interface Callback {
        void onUpdateAvailable(UpdateInfo info);
        void onNoUpdate();
        void onError(String message);
    }

    public static void check(String checkUrl, String currentVersion, int currentVersionCode, Callback callback) {
        new AsyncTask<Void, Void, Object>() {
            @Override
            protected Object doInBackground(Void... params) {
                try {
                    JSONObject json = new JSONObject(readText(checkUrl, "application/json"));
                    String remoteVersion = json.optString("version", "");
                    int remoteVersionCode = json.optInt("versionCode", 0);
                    String downloadUrl = json.optString("url", "");
                    String notes = json.optString("notes", "");

                    if (remoteVersion.isEmpty() || downloadUrl.isEmpty()) {
                        return "更新信息不完整";
                    }

                    return isNewer(remoteVersion, remoteVersionCode,
                            currentVersion, currentVersionCode)
                            ? new UpdateInfo(remoteVersion, remoteVersionCode, downloadUrl, notes)
                            : null;
                } catch (Exception e) {
                    Log.e(TAG, "check error", e);
                    return "检查失败: " + e.getMessage();
                }
            }

            @Override
            protected void onPostExecute(Object result) {
                deliver(result, callback);
            }
        }.execute();
    }

    /**
     * 通过 GitHub Releases API 检查更新，弹窗正文使用 Release 的描述内容。
     *
     * <p>版本号取自 tag（去掉 {@code v} 前缀），正文取 Release 的 {@code body}，
     * 下载地址取 Release 上传的 APK 资产，因此「更新内容」与 Release 页面完全一致。
     */
    public static void checkLatestRelease(String apiUrl, String currentVersion, int currentVersionCode,
                                          Callback callback) {
        new AsyncTask<Void, Void, Object>() {
            @Override
            protected Object doInBackground(Void... params) {
                try {
                    JSONObject release = new JSONObject(
                            readText(apiUrl, "application/vnd.github+json"));
                    String remoteVersion = normalizeTag(release.optString("tag_name", ""));
                    String notes = release.optString("body", "");
                    String downloadUrl = findApkUrl(release);

                    if (remoteVersion.isEmpty() || downloadUrl.isEmpty()) {
                        return "Release 信息不完整";
                    }

                    // Release 里没有 versionCode，时间戳版本号比较不依赖它
                    return isNewer(remoteVersion, 0, currentVersion, currentVersionCode)
                            ? new UpdateInfo(remoteVersion, 0, downloadUrl, notes)
                            : null;
                } catch (Exception e) {
                    Log.e(TAG, "check release error", e);
                    return "检查失败: " + e.getMessage();
                }
            }

            @Override
            protected void onPostExecute(Object result) {
                deliver(result, callback);
            }
        }.execute();
    }

    private static void deliver(Object result, Callback callback) {
        if (result instanceof UpdateInfo) {
            callback.onUpdateAvailable((UpdateInfo) result);
        } else if (result == null) {
            callback.onNoUpdate();
        } else {
            callback.onError((String) result);
        }
    }

    private static String readText(String urlText, String accept) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", accept);
            conn.setRequestProperty("User-Agent", "PlayAuto-Android");

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("服务器返回 " + code);
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            } finally {
                reader.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Release tag 形如 {@code v2026.11.16.1444}，去掉前缀得到版本号。 */
    static String normalizeTag(String tag) {
        if (tag == null) return "";
        String value = tag.trim();
        if (value.length() > 1
                && (value.charAt(0) == 'v' || value.charAt(0) == 'V')
                && Character.isDigit(value.charAt(1))) {
            value = value.substring(1);
        }
        return value;
    }

    /** 从 Release 资产中挑选 APK 下载地址，优先使用固定命名的安装包。 */
    static String findApkUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return "";
        String[] names = new String[assets.length()];
        String[] urls = new String[assets.length()];
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null) continue;
            names[index] = asset.optString("name", "");
            urls[index] = asset.optString("browser_download_url", "");
        }
        return pickApkUrl(names, urls);
    }

    /**
     * 挑选 APK 下载地址：优先 {@link #APK_ASSET_NAME}，其次任意 {@code .apk} 资产。
     *
     * <p>不依赖 {@code JSONObject} 才能被纯 JVM 自测覆盖（android.jar 里的 org.json 是桩实现）。
     */
    static String pickApkUrl(String[] names, String[] urls) {
        if (names == null || urls == null) return "";
        String fallback = "";
        int count = Math.min(names.length, urls.length);
        for (int index = 0; index < count; index++) {
            String name = names[index];
            String url = urls[index];
            if (name == null || name.isEmpty() || url == null || url.isEmpty()) continue;
            if (name.equals(APK_ASSET_NAME)) return url;
            if (fallback.isEmpty() && name.endsWith(".apk")) fallback = url;
        }
        return fallback;
    }

    /**
     * 判断远端版本是否比本地新。
     *
     * <p>本项目的 versionName 是构建时生成的 {@code YYYY.MM.DD.HHMM} 时间戳，天然单调递增，
     * 因此**以版本号字符串为唯一依据**；versionCode 只在任一侧版本号格式不符合时兜底。
     *
     * <p>曾经的问题：构建脚本把 versionCode 固定为 1，而 update.json 里写 2，
     * 旧逻辑优先比较 versionCode，导致「本地 2026.11.16 被提示升级到 2026.11.13」的降级误判。
     */
    static boolean isNewer(String remoteVersion, int remoteVersionCode,
                           String currentVersion, int currentVersionCode) {
        if (isTimestampVersion(remoteVersion) && isTimestampVersion(currentVersion)) {
            return remoteVersion.compareTo(currentVersion) > 0;
        }
        return remoteVersionCode > currentVersionCode;
    }

    /** 是否为 {@code YYYY.MM.DD.HHMM} 形式的 15 位版本号（构建脚本产出）。 */
    static boolean isTimestampVersion(String version) {
        if (version == null || version.length() != 15) return false;
        for (int index = 0; index < version.length(); index++) {
            char value = version.charAt(index);
            boolean separator = index == 4 || index == 7 || index == 10;
            if (separator) {
                if (value != '.') return false;
            } else if (value < '0' || value > '9') {
                return false;
            }
        }
        return true;
    }

    /** 更新说明为空时的兜底文案。 */
    static final String DEFAULT_NOTES = "包含若干功能改进与问题修复。";

    /**
     * 整理 Release 描述 / update.json 里的更新说明，供弹窗展示。
     *
     * <p>正文与 Release 描述（即提交信息）保持一致，只做必要的显示整理：
     * 把 JSON 里可能出现的字面量 {@code \n} 还原为换行、去掉行尾空白、
     * 连续空行压缩为一行、去掉首尾空行；内容为空时给出兜底文案。
     */
    static String formatNotes(String notes) {
        if (notes == null) return DEFAULT_NOTES;
        String text = notes.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r\n", "\n");
        StringBuilder cleaned = new StringBuilder(text.length());
        boolean lastBlank = true;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!lastBlank) cleaned.append('\n');
                lastBlank = true;
                continue;
            }
            if (cleaned.length() > 0 && !lastBlank) cleaned.append('\n');
            cleaned.append(trimmed);
            lastBlank = false;
        }
        String result = cleaned.toString().trim();
        return result.isEmpty() ? DEFAULT_NOTES : result;
    }
}