package cn.autoplay.replacement;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ErrorLog extends Application {
    private static final String TAG = "AutoPlayError";
    private static final int LIMIT = 128 * 1024;
    private static ErrorLog instance;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                record("未捕获异常，线程：" + thread.getName(), error);
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    private static SharedPreferences prefs() {
        return instance.getSharedPreferences("error_log", MODE_PRIVATE);
    }

    private static void save(SharedPreferences.Editor editor) {
        if (!editor.commit()) Log.e(TAG, "Cannot persist diagnostic state");
    }

    static synchronized void stage(String stage) {
        save(prefs().edit().putString("stage", stage));
        append("USB 阶段：" + stage);
    }

    static synchronized void diagnostic(String message) {
        Log.i(TAG, message);
        append(message);
    }

    static synchronized void record(String message, Throwable error) {
        String detail = message + "\nAndroid " + Build.VERSION.RELEASE + " / API "
                + Build.VERSION.SDK_INT + " / " + Build.MANUFACTURER + " " + Build.MODEL
                + "\n最后 USB 阶段：" + prefs().getString("stage", "尚未连接");
        if (error != null) detail += "\n" + Log.getStackTraceString(error);
        if (detail.length() > 24000) detail = detail.substring(0, 24000) + "\n[日志已截断]";
        Log.e(TAG, message, error);
        append(detail);
        save(prefs().edit().putString("pending", detail));
    }

    static synchronized String pending() {
        return prefs().getString("pending", "");
    }

    static synchronized void acknowledge(String detail) {
        if (detail.equals(pending())) save(prefs().edit().remove("pending"));
    }

    private static File file(boolean previous) {
        return new File(instance.getFilesDir(), previous ? "errors.previous.log" : "errors.log");
    }

    private static void append(String text) {
        try {
            File current = file(false);
            if (current.length() >= LIMIT) {
                File previous = file(true);
                if (previous.exists() && !previous.delete()) throw new IOException("Cannot rotate previous log");
                if (!current.renameTo(previous)) throw new IOException("Cannot rotate current log");
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
            try (FileOutputStream output = new FileOutputStream(current, true)) {
                output.write((time + "\n" + text + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (IOException exception) {
            Log.e(TAG, "Cannot write error log", exception);
            save(prefs().edit().putString("storage_error", exception.toString()));
        }
    }

    static synchronized String history() {
        StringBuilder text = new StringBuilder();
        text.append("Java 崩溃会在下次打开时提示；JNI 原生崩溃、强制终止和断电可能仅留下最后 USB 阶段。")
                .append("\n最后 USB 阶段：").append(prefs().getString("stage", "尚未连接"))
                .append("\n日志可能含设备信息或异常中的隐私内容，请谨慎分享。\n\n");
        for (boolean previous : new boolean[]{true, false}) {
            File source = file(previous);
            if (!source.exists()) continue;
            try (FileInputStream input = new FileInputStream(source)) {
                byte[] bytes = new byte[LIMIT + 100000];
                int size = 0;
                int count;
                while (size < bytes.length && (count = input.read(bytes, size, bytes.length - size)) != -1) {
                    size += count;
                }
                text.append(new String(bytes, 0, size, StandardCharsets.UTF_8));
            } catch (IOException exception) {
                Log.e(TAG, "Cannot read error log", exception);
                text.append("\n读取日志失败：").append(exception);
            }
        }
        String storageError = prefs().getString("storage_error", "");
        if (!storageError.isEmpty()) text.append("\n日志存储错误：").append(storageError);
        return text.toString();
    }

    static synchronized void clear() throws IOException {
        for (boolean previous : new boolean[]{true, false}) {
            File target = file(previous);
            if (target.exists() && !target.delete()) throw new IOException("无法清空 " + target.getName());
        }
        save(prefs().edit().remove("pending").remove("storage_error"));
    }
}
