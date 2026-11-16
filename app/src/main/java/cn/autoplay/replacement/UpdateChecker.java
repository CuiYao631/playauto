package cn.autoplay.replacement;

import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private static final String TAG = "UpdateChecker";

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
                    URL url = new URL(checkUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "application/json");

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        return "服务器返回 " + code;
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    conn.disconnect();

                    JSONObject json = new JSONObject(sb.toString());
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
                if (result instanceof UpdateInfo) {
                    callback.onUpdateAvailable((UpdateInfo) result);
                } else if (result == null) {
                    callback.onNoUpdate();
                } else {
                    callback.onError((String) result);
                }
            }
        }.execute();
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
}