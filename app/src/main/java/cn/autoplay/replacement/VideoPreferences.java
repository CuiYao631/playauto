package cn.autoplay.replacement;

import android.content.SharedPreferences;
import android.util.Log;

final class VideoPreferences {
    private VideoPreferences() { }

    static synchronized void migrate(SharedPreferences prefs) {
        if (prefs.getBoolean("original_video_options_v1", false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (prefs.contains("decoder_mode")) {
            int previous = prefs.getInt("decoder_mode", VideoOptions.HARDWARE);
            int selected = VideoOptions.migrateDecoder(previous);
            editor.putInt("decoder_mode", selected);
            if (previous != selected) Log.i("AutoPlayVideo", "Migrate decoder " + previous + " -> " + selected);
        }
        if (prefs.contains("frame_rate")) {
            editor.putInt("frame_rate", VideoOptions.migrateFrameRate(prefs.getInt("frame_rate", 30)));
        }
        if (prefs.contains("screen_dpi")) {
            int previous = prefs.getInt("screen_dpi", 1);
            if (previous < 0 || previous > 3) throw new IllegalArgumentException("Invalid old DPI index: " + previous);
            editor.putInt("screen_dpi", new int[]{120, 160, 240, 320}[previous]);
        }
        editor.putBoolean("original_video_options_v1", true).apply();
    }

    static int frameRate(SharedPreferences prefs) {
        return VideoOptions.effectiveFrameRate(prefs.getInt("decoder_mode", VideoOptions.HARDWARE),
                prefs.getInt("frame_rate", defaultFrameRate()));
    }

    static int defaultFrameRate() {
        String device = android.os.Build.MANUFACTURER + "-" + android.os.Build.MODEL + "-"
                + android.os.Build.VERSION.RELEASE + "(" + android.os.Build.VERSION.SDK_INT + ")";
        String product = android.os.Build.MANUFACTURER + "-" + android.os.Build.MODEL + "-"
                + android.os.Build.PRODUCT + "-" + android.os.Build.VERSION.RELEASE
                + "(" + android.os.Build.VERSION.SDK_INT + ")";
        return VideoOptions.defaultFrameRate(device, product);
    }

    static int dpi(SharedPreferences prefs, int width, int height) {
        if (prefs.getInt("decoder_mode", VideoOptions.HARDWARE) == VideoOptions.SOFTWARE) return 160;
        int selected = prefs.getInt("screen_dpi", 0);
        return selected == 0 ? VideoOptions.automaticDpi(width, height) : selected;
    }
}
