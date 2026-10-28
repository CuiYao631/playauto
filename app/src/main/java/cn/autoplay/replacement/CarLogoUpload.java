package cn.autoplay.replacement;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class CarLogoUpload {
    static final String DEFAULT_PATH = "CarLogo/Changan.png";

    private CarLogoUpload() { }

    static void send(Context context, BoxProtocolSession session, String path)
            throws IOException, JSONException {
        JSONArray logos = new JSONArray(new String(read(context, "carlogo.json"), StandardCharsets.UTF_8));
        JSONObject selected = null;
        for (int i = 0; i < logos.length(); i++) {
            JSONObject logo = logos.getJSONObject(i);
            if (path.equals(logo.optString("path"))) {
                selected = logo;
                break;
            }
        }
        if (selected == null) throw new IOException("汽车图标不存在：" + path);
        byte[] bytes = read(context, path);
        Bitmap source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (source == null) throw new IOException("无法解码 " + path);
        try {
            String lower = path.toLowerCase(Locale.ROOT);
            String name = selected.getString("name");
            session.setCarLogo(name, selected.optString("zh-CN", name), selected.optInt("brandID", 0),
                    !lower.contains("default") && !lower.contains("custom"),
                    png(source, 120), png(source, 180), png(source, 256));
        } finally {
            source.recycle();
        }
    }

    private static byte[] read(Context context, String path) throws IOException {
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static byte[] png(Bitmap source, int size) throws IOException {
        Bitmap scaled = Bitmap.createScaledBitmap(source, size, size, true);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG 编码失败");
            return output.toByteArray();
        } finally {
            if (scaled != source) scaled.recycle();
        }
    }
}
