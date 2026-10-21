package cn.autoplay.replacement;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class CompatibilityInstrumentation extends Instrumentation {
    private int assertions;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        boolean hadTheme = prefs.contains("theme_mode");
        int theme = prefs.getInt("theme_mode", 0);
        MainActivity activity = null;
        try {
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            MainActivity main = activity;
            waitForIdleSync();
            require(AndroidCompat.hasMicrophone(context), "Grant microphone permission before this test");
            Drawable icon = context.getPackageManager().getApplicationIcon(context.getPackageName());
            Bitmap bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 192, 192);
            icon.draw(new Canvas(bitmap));
            require(android.graphics.Color.alpha(bitmap.getPixel(96, 96)) == 255,
                    "Launcher icon must render");
            bitmap.recycle();
            for (int mode : new int[]{1, 2}) {
                runOnMainSync(() -> {
                    prefs.edit().putInt("theme_mode", mode).commit();
                    main.refreshTheme();
                    main.showSettings();
                    main.showMore();
                    main.showSteeringLearning();
                    main.showEnvironmentCheck();
                });
                android.os.SystemClock.sleep(850);
                waitForIdleSync();
                runOnMainSync(main::showMain);
                require(main.getWindow().getDecorView().getWidth() > 0,
                        "Light/dark home and settings must remain laid out");
            }
            require(AndroidCompat.decoders().length > 0, "Codec enumeration must work");
            require(cn.manstep.phonemirrorBox.BoxInterface.BoxCore.isAvailable(),
                    "Native protocol library must load: "
                            + cn.manstep.phonemirrorBox.BoxInterface.BoxCore.getLoadError());
            testAudio();
            testLegacyControls(context);
            result.putBoolean("compatibility_passed", true);
            result.putInt("assertions", assertions);
            result.putInt("android_api", android.os.Build.VERSION.SDK_INT);
            result.putString("hardware_scope", "No USB box or phone projection validation");
        } catch (Exception | AssertionError failure) {
            result.putString("failure", android.util.Log.getStackTraceString(failure));
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            if (hadTheme) editor.putInt("theme_mode", theme);
            else editor.remove("theme_mode");
            editor.commit();
            if (activity != null) {
                MainActivity main = activity;
                runOnMainSync(() -> {
                    main.refreshTheme();
                    main.finish();
                });
            }
        }
        finish(result.getBoolean("compatibility_passed", false)
                ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void testAudio() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        try (AudioPlayback playback = new AudioPlayback(error::set)) {
            byte[] payload = ByteBuffer.allocate(12 + 3200).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(5).putFloat(1).putInt(1).array();
            playback.submit(AudioFrame.parse(payload));
            Field field = AudioPlayback.class.getDeclaredField("audioExecutor");
            field.setAccessible(true);
            ((ThreadPoolExecutor) field.get(playback)).submit(() -> { }).get(3, TimeUnit.SECONDS);
            require(error.get() == null, "PCM output must work: " + error.get());
        }
        CountDownLatch pcm = new CountDownLatch(1);
        try (AudioCapture capture = new AudioCapture(error::set)) {
            capture.start(5, (format, bytes) -> {
                if (bytes.length > 0 && (bytes.length & 1) == 0) pcm.countDown();
            });
            require(pcm.await(3, TimeUnit.SECONDS), "Microphone must return PCM16 data");
            require(error.get() == null, "Microphone capture must not fail: " + error.get());
        }
    }

    private void testLegacyControls(Context context) throws Exception {
        CountDownLatch bound = new CountDownLatch(1);
        AtomicReference<ConnectionService> service = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service.set(((ConnectionService.LocalBinder) binder).getService());
                bound.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        require(context.bindService(new Intent(context, ConnectionService.class),
                connection, Context.BIND_AUTO_CREATE), "Compatibility service must bind");
        try {
            require(bound.await(3, TimeUnit.SECONDS), "Compatibility service must become ready");
            Constructor<?> constructor = Class.forName(MediaControls.class.getName() + "$Legacy")
                    .getDeclaredConstructor(ConnectionService.class);
            constructor.setAccessible(true);
            runOnMainSync(() -> {
                try {
                    MediaControls controls = (MediaControls) constructor.newInstance(service.get());
                    try {
                        controls.setActive(true);
                        controls.setActive(false);
                    } finally {
                        controls.release();
                    }
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            });
            assertions++;
        } finally {
            context.unbindService(connection);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
