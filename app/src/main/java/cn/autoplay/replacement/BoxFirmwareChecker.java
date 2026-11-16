package cn.autoplay.replacement;

import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 盒子固件检查与下载，对应原版 APK 的 {@code /a/upgrade/checkBox} 与 {@code /a/upgrade/down}。
 *
 * <p>请求为 {@code POST} + {@code application/x-www-form-urlencoded}，公共头
 * {@code OS / User-Agent / Authorization / Accept-Language} 与原版一致。
 *
 * <p>{@code ver / uuid / mfd / model} 必须取自盒子通过 {@code CMD_BOX_SOFTWARE_VERSION}(204)
 * 与 {@code CMD_BOX_INFO}(25) 上报的真实值，否则服务端匹配不到对应固件。
 */
public final class BoxFirmwareChecker {

    private static final String TAG = "BoxFirmwareChecker";

    /** 原版硬编码的服务端基址（http，非 https）。 */
    public static final String DEFAULT_BASE_URL = "http://api.paplink.cn";
    /** 原版固定产品线编号。 */
    private static final String PRODUCT_CODE = "36";
    /** 下载落盘文件名，与盒子侧路径同名。 */
    public static final String FIRMWARE_FILE_NAME = "Auto_Box_Update.img";

    private BoxFirmwareChecker() {
    }

    /** 固件信息，字段与 checkBox 响应一一对应。 */
    public static final class Info {
        public final String version;
        public final String notes;
        /** 兼作下载校验值（md5）。 */
        public final String id;
        public final long size;
        public final boolean forced;

        Info(String version, String notes, String id, long size, boolean forced) {
            this.version = version;
            this.notes = notes;
            this.id = id;
            this.size = size;
            this.forced = forced;
        }
    }

    public interface Callback {
        /**
         * @param info  服务端最新固件信息；{@code null} 表示已是最新
         * @param token 本次请求返回的 token，下载时需作为 Authorization 带上
         */
        void onResult(Info info, String token);

        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(long downloaded, long total);

        void onSuccess(File file);

        void onError(String message);
    }

    /**
     * 查询盒子固件更新。
     *
     * @param baseUrl    服务端基址，通常 {@link #DEFAULT_BASE_URL}
     * @param token      上一步返回的 token，首次可为空
     * @param appVersion 当前 App 版本
     * @param boxVersion 盒子固件版本（15 位 {@code YYYY.MM.DD.HHMM}）
     * @param boxUuid    盒子 UUID
     * @param manufacturer 盒子制造商（CMD_BOX_INFO 的 mfd）
     * @param model      盒子型号（CMD_BOX_INFO 的 model）
     * @param firmwareName 固件文件名，通常 {@link #FIRMWARE_FILE_NAME}
     */
    public static void check(String baseUrl, String token, String appVersion, String boxVersion,
                             String boxUuid, String manufacturer, String model,
                             String firmwareName, Callback callback) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", PRODUCT_CODE);
        form.put("lang", "1");
        form.put("appVer", safe(appVersion));
        form.put("ver", safe(boxVersion));
        form.put("uuid", safe(boxUuid));
        form.put("mfd", safe(manufacturer));
        form.put("fwn", safe(firmwareName));
        form.put("model", safe(model));

        new AsyncTask<Void, Void, Object>() {
            @Override
            protected Object doInBackground(Void... params) {
                try {
                    String body = post(baseUrl + "/a/upgrade/checkBox", token, form);
                    JSONObject json = new JSONObject(stripPrefix(body));
                    int err = json.optInt("err", -1);
                    if (err != 0) {
                        return "服务端返回 err=" + err;
                    }
                    String remoteVersion = json.optString("ver", "");
                    String remoteId = json.optString("id", "");
                    String notes = json.optString("notes", "");
                    long size = parseLong(json.optString("size", "0"));
                    boolean forced = json.optInt("forced", 0) == 1;
                    String newToken = json.optString("token", token);
                    if (remoteVersion.isEmpty()) {
                        return "服务端未返回固件版本";
                    }
                    // 无更新时原版把 id 置空并把 size 置 -200。
                    boolean hasUpdate = !remoteId.isEmpty()
                            && compareVersion(remoteVersion, boxVersion) > 0;
                    return new Object[]{hasUpdate
                            ? new Info(remoteVersion, notes, remoteId, size, forced) : null, newToken};
                } catch (Exception exception) {
                    Log.e(TAG, "checkBox failed", exception);
                    return "检查盒子固件失败：" + exception.getMessage();
                }
            }

            @Override
            protected void onPostExecute(Object result) {
                if (result instanceof String) {
                    callback.onError((String) result);
                    return;
                }
                Object[] payload = (Object[]) result;
                callback.onResult((Info) payload[0], (String) payload[1]);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** 下载固件到应用缓存目录，返回 {@code Auto_Box_Update.img}。 */
    public static void download(String baseUrl, String token, Info info,
                                File targetDir, DownloadCallback callback) {
        new AsyncTask<Void, Long, Object>() {
            @Override
            protected Object doInBackground(Void... params) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(baseUrl + "/a/upgrade/down");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    applyHeaders(conn, token);
                    conn.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded; charset=UTF-8");

                    Map<String, String> form = new LinkedHashMap<>();
                    form.put("ver", info.version);
                    form.put("id", info.id);
                    byte[] body = buildForm(form).getBytes(StandardCharsets.UTF_8);
                    conn.getOutputStream().write(body);

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        return "下载失败，HTTP " + code;
                    }
                    long total = conn.getContentLengthLong();
                    File target = new File(targetDir, FIRMWARE_FILE_NAME);
                    if (targetDir != null && !targetDir.exists() && !targetDir.mkdirs()) {
                        return "无法创建固件缓存目录";
                    }
                    MessageDigest digest = MessageDigest.getInstance("MD5");
                    long downloaded = 0;
                    try (InputStream input = conn.getInputStream();
                         FileOutputStream output = new FileOutputStream(target)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            downloaded += read;
                            publishProgress(downloaded, total);
                        }
                    }
                    if (downloaded <= 0) {
                        return "下载内容为空";
                    }
                    if (downloaded > BoxProtocolSession.MAX_FIRMWARE_SIZE) {
                        target.delete();
                        return "固件包超过 50MB 上限";
                    }
                    String md5 = toHex(digest.digest());
                    if (!info.id.isEmpty() && !info.id.equalsIgnoreCase(md5)) {
                        target.delete();
                        return "固件校验值不匹配（期望 " + info.id + "，实际 " + md5 + "）";
                    }
                    return target;
                } catch (Exception exception) {
                    Log.e(TAG, "download firmware failed", exception);
                    return "下载固件失败：" + exception.getMessage();
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }

            @Override
            protected void onProgressUpdate(Long... values) {
                callback.onProgress(values[0], values[1]);
            }

            @Override
            protected void onPostExecute(Object result) {
                if (result instanceof File) {
                    callback.onSuccess((File) result);
                } else {
                    callback.onError(String.valueOf(result));
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    static String buildForm(Map<String, String> form) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (builder.length() > 0) builder.append('&');
            builder.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            builder.append('=');
            builder.append(URLEncoder.encode(safe(entry.getValue()), "UTF-8"));
        }
        return builder.toString();
    }

    private static String post(String url, String token, Map<String, String> form)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            applyHeaders(conn, token);
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] body = buildForm(form).getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(body);

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            InputStream input = conn.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            input.close();
            return output.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static void applyHeaders(HttpURLConnection conn, String token) {
        conn.setRequestProperty("OS", "Android");
        conn.setRequestProperty("User-Agent",
                "Android/" + android.os.Build.VERSION.SDK_INT + "/"
                        + android.os.Build.VERSION.RELEASE + " "
                        + android.os.Build.MANUFACTURER);
        conn.setRequestProperty("Authorization", safe(token));
        conn.setRequestProperty("Accept-Language", "zh_CN");
    }

    /** 原版响应可能带 {@code xxx$json} 前缀，这里取 {@code $} 之后的部分。 */
    private static String stripPrefix(String body) {
        if (body == null) return "";
        int index = body.indexOf('$');
        return index >= 0 && index < body.length() - 1 ? body.substring(index + 1) : body;
    }

    /** 版本号是 {@code YYYY.MM.DD.HHMM}，字典序即时间序。 */
    static int compareVersion(String left, String right) {
        return safe(left).compareTo(safe(right));
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
