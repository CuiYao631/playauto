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

                    if (remoteVersionCode > currentVersionCode) {
                        return new UpdateInfo(remoteVersion, remoteVersionCode, downloadUrl, notes);
                    }

                    return null; // no update
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
}