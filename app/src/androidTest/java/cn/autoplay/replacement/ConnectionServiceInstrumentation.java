package cn.autoplay.replacement;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Hardware-free foreground lifecycle regression on API 28-33.
 * Starts the service directly to isolate lifecycle from USB authorization.
 */
public final class ConnectionServiceInstrumentation extends Instrumentation {
    private ConnectionService observed;
    private ServiceConnection observer;
    private boolean observerBound;
    private int assertions;
    private String nativeUsbValidation;
    private boolean verifyVideoPixels;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        verifyVideoPixels = arguments != null && "true".equals(arguments.getString("videoPixels"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        boolean hadPreference = prefs.contains(ConnectionService.PREFERENCE);
        boolean previous = prefs.getBoolean(ConnectionService.PREFERENCE, true);
        try {
            require(Build.VERSION.SDK_INT >= 28 && Build.VERSION.SDK_INT <= 33,
                    "Hardware-free foreground test requires API 28-33; API 34+ needs a real USB grant");
            prefs.edit().putBoolean(ConnectionService.PREFERENCE, true).commit();
            Activity activity = launchActivity();
            testOriginalVideoPreferences();
            testThemeTransitionBackground((MainActivity) activity);
            android.graphics.drawable.Drawable launcherIcon =
                    context.getPackageManager().getApplicationIcon(context.getPackageName());
            require(launcherIcon instanceof android.graphics.drawable.BitmapDrawable,
                    "Launcher must use the supplied PNG without adaptive layers");
            android.graphics.Bitmap iconBitmap = android.graphics.Bitmap.createBitmap(
                    108, 108, android.graphics.Bitmap.Config.ARGB_8888);
            launcherIcon.setBounds(0, 0, 108, 108);
            launcherIcon.draw(new android.graphics.Canvas(iconBitmap));
            require(android.graphics.Color.alpha(iconBitmap.getPixel(54, 54)) == 255,
                    "Launcher icon must render an opaque center");
            iconBitmap.recycle();
            bindObserver();
            ConnectionService original = observed;
            require(original.usbDiagnostics().contains("API " + Build.VERSION.SDK_INT)
                            && original.usbDiagnostics().contains("USB 设备数：0"),
                    "USB diagnostics must expose OS and absent-device status");
            String[] requestedPermissions = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS)
                    .requestedPermissions;
            require(requestedPermissions != null && java.util.Arrays.asList(requestedPermissions)
                            .contains("android.permission.MANAGE_USB"),
                    "Manifest must request the original privileged USB management permission");
            require(original.usbDiagnostics().contains("系统应用：否")
                            && original.usbDiagnostics().contains("MANAGE_USB：未授予")
                            && original.usbDiagnostics().contains("尚未尝试旧系统授权"),
                    "Normal installation must honestly report missing system privileges without JNI");
            runOnMainSync(() -> {
                observed.retryUsbPermission();
                require(observed.boxStatus.contains("未检测到支持的 USB 设备"),
                        "Retry without USB hardware must explicitly report absence");
            });
            testNativeUsbTransport((MainActivity) activity);
            testRootUsbAuthorization((MainActivity) activity);
            testSystemAppConversion();
            testPhoneNightMode();
            require(!isServiceRunning(true), "No box must not start foreground protection automatically");
            testRotationLayout(activity);
            testOpenH264Decoder();
            testVideoDecodeRecovery((MainActivity) activity, 3);
            result.putString("openh264_pixels", "passed");
            result.putString("video_pixels", verifyVideoPixels ? "requested" : "not_run");
            if (verifyVideoPixels) {
                testVideoDecodeRecovery((MainActivity) activity, 0);
                result.putString("video_pixels", "passed");
            }
            testHomeConnectionStates(activity);
            testSteeringLearning((MainActivity) activity);
            AudioPlayback audio = new AudioPlayback(error -> { throw new AssertionError(error); });
            audio.close();
            audio.close();
            assertions++;

            Intent serviceIntent = new Intent(context, ConnectionService.class);
            context.startForegroundService(serviceIntent);
            awaitService(true, true);
            unbindObserver();

            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            require(isServiceRunning(true), "Foreground service must survive switching apps");

            runOnMainSync(activity::finish);
            waitForIdleSync();
            require(isServiceRunning(true), "Foreground service must survive Activity destruction");

            Activity replacement = launchActivity();
            bindObserver();
            require(observed == original, "Recreated Activity must reuse the same service");
            runOnMainSync(() -> {
                prefs.edit().putBoolean(ConnectionService.PREFERENCE, false).commit();
                observed.updateBackgroundPolicy();
            });
            awaitService(true, false);
            unbindObserver();
            runOnMainSync(replacement::finish);
            waitForIdleSync();
            awaitService(false, false);
            result.putBoolean("lifecycle_passed", true);
            result.putInt("assertions", assertions);
            result.putString("native_usb_validation", nativeUsbValidation);
            result.putString("hardware_scope", "No USB/phone continuity or modern microphone validation");
        } catch (Exception | AssertionError failure) {
            result.putString("failure", android.util.Log.getStackTraceString(failure));
        } finally {
            unbindObserver();
            SharedPreferences.Editor editor = prefs.edit();
            if (hadPreference) editor.putBoolean(ConnectionService.PREFERENCE, previous);
            else editor.remove(ConnectionService.PREFERENCE);
            editor.commit();
            runOnMainSync(() -> {
                if (observed != null) observed.updateBackgroundPolicy();
            });
        }
        finish(result.getBoolean("lifecycle_passed", false) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void testNativeUsbTransport(MainActivity activity) throws Exception {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        boolean hadMode = prefs.contains("usb_transport");
        int previousMode = prefs.getInt("usb_transport", 0);
        try {
            runOnMainSync(() -> {
                prefs.edit().remove("usb_transport").commit();
                require(observed.usbDiagnostics().contains("选择通道：Android USB"),
                        "Android USB must remain the default");
                activity.showSettings();
                require(containsText(activity.getWindow().getDecorView(), "USB 通道"),
                        "Settings must expose the USB transport selector");
                prefs.edit().putInt("usb_transport", 1).commit();
                activity.onUsbTransportChanged();
                observed.retryUsbPermission();
                require(observed.usbDiagnostics().contains("选择通道：Native USB")
                                && !observed.isNativeUsbConnected(),
                        "Selecting native USB without a box must not claim a connection");
                require(observed.boxStatus.contains("未检测到支持的 USB 设备"),
                        "Native retry without a box must report absence");
                activity.showMain();
            });
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            if (hadMode) editor.putInt("usb_transport", previousMode);
            else editor.remove("usb_transport");
            editor.commit();
        }

        FakeUsbDriver driver = new FakeUsbDriver();
        NativeUsbConnection transport = NativeUsbConnection.open(driver, 0x1314, 0x1520);
        try {
            byte[] data = {9, 9, 9, 9, 9, 9};
            require(transport.bulkRead(data, 2, 3, 250) == 3
                            && java.util.Arrays.equals(data, new byte[]{9, 9, 1, 2, 3, 9}),
                    "Native reads must copy only the returned bytes at the requested offset");
            require(transport.bulkWrite(data, 2, 3, 1000) == 3
                            && java.util.Arrays.equals(driver.written, new byte[]{1, 2, 3}),
                    "Native writes must honor offsets without sending surrounding bytes");
            require(transport.bulkWrite(new byte[20000], 0, 20000, 5000) == 16384
                            && driver.timeout == 1000,
                    "Native USB must cap each transfer at 16 KiB and use bounded timeouts");
            FakeUsbDriver duplicate = new FakeUsbDriver();
            try {
                NativeUsbConnection.open(duplicate, 0x1314, 0x1520);
                throw new AssertionError("Native global state must have one owner");
            } catch (java.io.IOException expected) {
                require(duplicate.initializations == 0 && duplicate.releases == 0,
                        "Rejected duplicate must not touch the active native context");
            }
        } finally {
            transport.close();
            transport.close();
        }
        require(driver.releases == 1, "Native release must be idempotent");
        for (int error : new int[]{-7, -4, 100}) testNativeTransferError(error);
        testNativeCloseWaits();
        try {
            transport.bulkRead(new byte[1], 0, 1, 250);
            throw new AssertionError("Closed native transport must reject reads");
        } catch (java.io.IOException expected) {
            assertions++;
        }
        FakeUsbDriver failed = new FakeUsbDriver();
        failed.openResult = -3;
        try {
            NativeUsbConnection.open(failed, 0x1314, 0x1520);
            throw new AssertionError("Native access failure must be explicit");
        } catch (java.io.IOException expected) {
            require(failed.releases == 1 && expected.getMessage().contains("-3"),
                    "Failed native open must release context and preserve error");
        }

        require(cn.manstep.phonemirrorBox.BoxInterface.BoxConnection.isAvailable(),
                "Bundled libconnection must load: "
                        + cn.manstep.phonemirrorBox.BoxInterface.BoxConnection.getLoadError());
        cn.manstep.phonemirrorBox.BoxInterface.BoxConnection nativeUsb =
                new cn.manstep.phonemirrorBox.BoxInterface.BoxConnection();
        int initResult;
        try {
            initResult = nativeUsb.init();
            if (initResult == 0) {
                int devices = nativeUsb.getDeviceList(new String[256]);
                require(devices == 0, "Hardware-free native USB enumeration must report no devices");
                nativeUsbValidation = "Library loaded; native enumeration found no devices";
            } else {
                String error = cn.manstep.phonemirrorBox.BoxInterface.BoxConnection.getError(initResult);
                require(error != null && !error.isEmpty(), "Native init failure must expose an error description");
                nativeUsbValidation = "Library loaded; native init blocked: " + initResult + " / " + error;
                android.util.Log.w("PlayAutoTest", nativeUsbValidation);
            }
        } finally {
            nativeUsb.release();
        }
        try (NativeUsbConnection ignored = NativeUsbConnection.open(0x1314, 0x1520)) {
            throw new AssertionError("Native USB must not open an absent box");
        } catch (java.io.IOException expected) {
            require(expected.getMessage().contains(initResult == 0
                            ? "Native USB 打开失败" : "Native USB 初始化失败"),
                    "Real native open must explicitly report no device or initialization denial");
        }
    }

    private void testNativeTransferError(int code) throws Exception {
        FakeUsbDriver driver = new FakeUsbDriver();
        driver.result = code;
        try (NativeUsbConnection transport = NativeUsbConnection.open(driver, 0x1314, 0x1520)) {
            try {
                transport.bulkWrite(new byte[6], 0, 6, 250);
                throw new AssertionError("Native transfer error must not be silently retried");
            } catch (java.io.IOException expected) {
                require(expected.getMessage().contains(Integer.toString(code)),
                        "Native invalid result must preserve its code");
            }
            driver.result = 1;
            try {
                transport.bulkRead(new byte[6], 0, 6, 250);
                throw new AssertionError("A failed native transfer must poison the session");
            } catch (java.io.IOException expected) {
                require(expected.getMessage().contains(Integer.toString(code)),
                        "Both directions must stop after unknown partial transfer length");
            }
        }
    }

    private void testNativeCloseWaits() throws Exception {
        FakeUsbDriver driver = new FakeUsbDriver();
        driver.readEntered = new CountDownLatch(1);
        driver.allowRead = new CountDownLatch(1);
        NativeUsbConnection transport = NativeUsbConnection.open(driver, 0x1314, 0x1520);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                transport.bulkRead(new byte[1], 0, 1, 250);
            } catch (Exception | AssertionError error) {
                failure.set(error);
            }
        });
        Thread closer = new Thread(() -> {
            try {
                transport.close();
            } catch (Exception | AssertionError error) {
                failure.set(error);
            } finally {
                closed.countDown();
            }
        });
        try {
            reader.start();
            require(driver.readEntered.await(2, TimeUnit.SECONDS), "Fake native read must start");
            require(transport.bulkWrite(new byte[1], 0, 1, 250) == 1,
                    "Native output must proceed while input is waiting");
            closer.start();
            require(!closed.await(50, TimeUnit.MILLISECONDS) && driver.releases == 0,
                    "Release must wait for the in-flight native read");
        } finally {
            driver.allowRead.countDown();
            reader.join(2000);
            closer.join(2000);
            transport.close();
        }
        require(failure.get() == null && closed.getCount() == 0 && driver.releases == 1,
                "Native close must finish once, after the read: " + failure.get());
    }

    private static final class FakeUsbDriver implements NativeUsbConnection.Driver {
        int initializations;
        int releases;
        int result = Integer.MIN_VALUE;
        int openResult;
        int timeout;
        byte[] written;
        CountDownLatch readEntered;
        CountDownLatch allowRead;
        public int init() { initializations++; return 0; }
        public int openDevice(int vid, int pid) { return openResult; }
        public int read(byte[] data, int length, int timeoutMillis) {
            timeout = timeoutMillis;
            if (readEntered != null) {
                readEntered.countDown();
                try {
                    if (!allowRead.await(2, TimeUnit.SECONDS)) throw new AssertionError("Fake native read timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            for (int i = 0; i < length; i++) data[i] = (byte) (i + 1);
            return result == Integer.MIN_VALUE ? length : result;
        }
        public int write(byte[] data, int length, int timeoutMillis) {
            timeout = timeoutMillis;
            written = java.util.Arrays.copyOf(data, length);
            return result == Integer.MIN_VALUE ? length : result;
        }
        public String error(int code) { return Integer.toString(code); }
        public void release() { releases++; }
    }

    private void testThemeTransitionBackground(MainActivity activity) {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        boolean hadTheme = prefs.contains("theme_mode");
        int previous = prefs.getInt("theme_mode", 0);
        try {
            runOnMainSync(() -> {
                prefs.edit().remove("theme_mode").commit();
                activity.refreshTheme();
                require(Ui.TEXT == android.graphics.Color.parseColor("#F3F4F6"),
                        "No saved theme, including after reset, must select dark mode");
            });
            for (int mode : new int[]{2, 1, 2}) {
                runOnMainSync(() -> {
                    prefs.edit().putInt("theme_mode", mode).commit();
                    activity.refreshTheme();
                    Runnable[] pages = {activity::showSettings, activity::showMore,
                            activity::showAdvanced, activity::showSteeringLearning, activity::showMain};
                    for (Runnable page : pages) {
                        page.run();
                        android.graphics.drawable.Drawable background =
                                activity.getWindow().getDecorView().getBackground();
                        android.graphics.Rect originalBounds =
                                new android.graphics.Rect(background.getBounds());
                        android.graphics.Bitmap pixel = android.graphics.Bitmap.createBitmap(
                                1, 1, android.graphics.Bitmap.Config.ARGB_8888);
                        try {
                            background.setBounds(0, 0, 1, 1);
                            background.draw(new android.graphics.Canvas(pixel));
                            int color = pixel.getPixel(0, 0);
                            require(android.graphics.Color.alpha(color) == 255,
                                    "Page fade must expose an opaque window background");
                            require(mode == 2
                                            ? android.graphics.Color.red(color) < 40
                                            : android.graphics.Color.red(color) > 220,
                                    "Window behind page fades must follow the selected theme");
                        } finally {
                            background.setBounds(originalBounds);
                            pixel.recycle();
                        }
                    }
                });
            }
        } finally {
            runOnMainSync(() -> {
                SharedPreferences.Editor editor = prefs.edit();
                if (hadTheme) editor.putInt("theme_mode", previous);
                else editor.remove("theme_mode");
                editor.commit();
                activity.refreshTheme();
            });
        }
    }

    private void testPhoneNightMode() throws Exception {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        java.util.Map<String, ?> previous = prefs.getAll();
        String[] keys = {"night_mode", "night_schedule", "night_start", "night_end"};
        Method setting = ConnectionService.class.getDeclaredMethod("currentNightSetting");
        setting.setAccessible(true);
        try {
            prefs.edit().remove("night_mode").putBoolean("night_schedule", true)
                    .putString("night_start", "invalid").putString("night_end", "invalid").commit();
            require((Integer) setting.invoke(observed) == -1,
                    "Default must follow phone even with a stale enabled schedule");
            for (int mode : new int[]{0, 1, 2}) {
                prefs.edit().putInt("night_mode", mode).commit();
                require((Integer) setting.invoke(observed) == (mode == 0 ? 1 : mode == 1 ? 0 : -1),
                        "Manual night/day and phone control must ignore vehicle time");
            }
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : keys) {
                Object value = previous.get(key);
                if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof String) editor.putString(key, (String) value);
                else editor.remove(key);
            }
            editor.commit();
        }
    }

    private Activity launchActivity() {
        Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
        return activity;
    }


    private Object activityField(Activity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private void setActivityField(Activity activity, String name, int value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(activity, value);
    }

    private void testOpenH264Decoder() throws Exception {
        byte[] encoded = android.util.Base64.decode(
                "AAAAAWdCwArcQmwEQAAAAwBAAAAPI8SJ4AAAAAFozg8sgAAAAWWIhAS8mKAAKU0nJyddddddddddddeA",
                android.util.Base64.DEFAULT);
        cn.manstep.phonemirrorBox.OpenH264Decoder decoder = new cn.manstep.phonemirrorBox.OpenH264Decoder();
        try {
            try {
                new cn.manstep.phonemirrorBox.OpenH264Decoder();
                throw new AssertionError("Native global decoder must reject a second owner");
            } catch (java.io.IOException expected) {
                require(expected.getMessage().contains("正在使用"), "Singleton conflict must be explicit");
            }
            byte[] output = new byte[6144];
            require(decoder.decode(encoded, output) == output.length
                            && decoder.width() == 64 && decoder.height() == 64,
                    "Original JNI must decode real baseline H.264 to packed 64x64 I420");
            require((output[32 * 64 + 32] & 255) > 140
                            && Math.abs((output[4096] & 255) - 128) < 3
                            && Math.abs((output[5120] & 255) - 128) < 3,
                    "Original decoder must produce nonblack neutral Y/U/V pixels");
        } finally {
            decoder.close();
            decoder.close();
        }
        try {
            decoder.decode(encoded, new byte[6144]);
            throw new AssertionError("Closed native decoder must reject input");
        } catch (java.io.IOException expected) {
            require(expected.getMessage().contains("已关闭"), "Closed decoder fails explicitly");
        }
        try (cn.manstep.phonemirrorBox.OpenH264Decoder reopened =
                     new cn.manstep.phonemirrorBox.OpenH264Decoder()) {
            require(reopened.decode(encoded, new byte[6144]) == 6144,
                    "Native decoder ownership must be reusable after release");
        }
        try (cn.manstep.phonemirrorBox.OpenH264Decoder reference =
                     new cn.manstep.phonemirrorBox.OpenH264Decoder()) {
            String[] nals = {
                    "AAAAAWdCwArZBCbARAAAAwAEAAADAPI8SJkg",
                    "AAAAAWjLg8sg",
                    "AAABZYiEBLyYoAAu2ycnJ1111111111114A=",
                    "AAAAAUGaOAr8TigAChbifE+J8/n8/n8/n8/n8/n8/n8=",
                    "AAAAAUGaVAK/E4oAAjbYnxPifP5/P5/P5/P5/P5/P5/A"
            };
            byte[] output = new byte[6144];
            for (int i = 0; i < nals.length; i++) {
                int size = reference.decode(android.util.Base64.decode(nals[i], android.util.Base64.DEFAULT), output);
                if (i >= 2) {
                    int y = output[32 * 64 + 32] & 255;
                    require(size == 6144 && Math.abs(y - (60 + (i - 2) * 40)) <= 3,
                            "Original decoder must preserve IDR/P/P reference order and luma: " + i + "/" + y);
                }
            }
        }
    }

    private void testOriginalVideoPreferences() {
        SharedPreferences prefs = getTargetContext().getSharedPreferences(
                "video-options-test-" + System.nanoTime(), Context.MODE_PRIVATE);
        try {
            VideoPreferences.migrate(prefs);
            require(prefs.getInt("decoder_mode", VideoOptions.HARDWARE) == VideoOptions.HARDWARE
                            && VideoPreferences.frameRate(prefs) == VideoPreferences.defaultFrameRate(),
                    "Fresh display defaults must match original hardware and device frame rate");
            prefs.edit().remove("original_video_options_v1").putInt("decoder_mode", 2)
                    .putInt("frame_rate", 1).putInt("screen_dpi", 3).commit();
            VideoPreferences.migrate(prefs);
            require(prefs.getInt("decoder_mode", -1) == VideoOptions.SOFTWARE
                            && prefs.getInt("frame_rate", -1) == 60
                            && prefs.getInt("screen_dpi", -1) == 320,
                    "Migrate old decoder and fps/DPI indices without resetting explicit values");
            require(VideoPreferences.frameRate(prefs) == 25 && VideoPreferences.dpi(prefs, 1280, 720) == 160,
                    "Original software handshake overrides fps and DPI");
            prefs.edit().putInt("decoder_mode", VideoOptions.HARDWARE).commit();
            VideoPreferences.migrate(prefs);
            require(VideoPreferences.frameRate(prefs) == 60
                            && VideoPreferences.dpi(prefs, 1280, 720) == 320,
                    "Returning to hardware restores selected fps and explicit DPI");
            prefs.edit().putInt("screen_dpi", 0).commit();
            require(VideoPreferences.dpi(prefs, 1280, 720) == VideoOptions.automaticDpi(1280, 720),
                    "Auto DPI uses original screen-aspect-based calculation");
        } finally {
            prefs.edit().clear().commit();
        }
    }

    private void testVideoDecodeRecovery(MainActivity activity, int mode) throws Exception {
        // Locally generated 64x64 gray baseline frame: SPS/PPS/IDR, with no SEI.
        byte[] encoded = android.util.Base64.decode(
                "AAAAAWdCwArcQmwEQAAAAwBAAAAPI8SJ4AAAAAFozg8sgAAAAWWIhAS8mKAAKU0nJyddddddddddddeA",
                android.util.Base64.DEFAULT);
        java.nio.ByteBuffer payload = java.nio.ByteBuffer.allocate(VideoFrame.METADATA_SIZE + encoded.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        payload.putInt(64).putInt(64).putInt(0).putLong(0).put(encoded);
        VideoFrame frame = VideoFrame.parse(payload.array(), false);
        byte[] slice = android.util.Base64.decode("AAAAAWWIhAS8mKAAKU0nJyddddddddddddeA",
                android.util.Base64.DEFAULT);
        java.nio.ByteBuffer nextPayload = java.nio.ByteBuffer.allocate(VideoFrame.METADATA_SIZE + slice.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        nextPayload.putInt(64).putInt(64).putInt(0).putLong(0).put(slice);
        VideoFrame nextFrame = VideoFrame.parse(nextPayload.array(), false);
        if (mode == 0) {
        android.media.MediaCodec verifier = android.media.MediaCodec.createDecoderByType("video/avc");
        try {
            verifier.configure(android.media.MediaFormat.createVideoFormat("video/avc", 64, 64), null, null, 0);
            verifier.start();
            boolean verified = false;
            int submitted = 0;
            long until = SystemClock.elapsedRealtime() + 3000;
            android.media.MediaCodec.BufferInfo decoded = new android.media.MediaCodec.BufferInfo();
            while (!verified && SystemClock.elapsedRealtime() < until) {
                int input = submitted < 2 ? verifier.dequeueInputBuffer(10000) : -1;
                if (input >= 0) {
                    java.nio.ByteBuffer data = verifier.getInputBuffer(input);
                    data.clear();
                    if (submitted == 0) data.put(encoded);
                    verifier.queueInputBuffer(input, 0, submitted == 0 ? encoded.length : 0,
                            submitted * 33333L, submitted == 0 ? 0 : android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    submitted++;
                }
                int output = verifier.dequeueOutputBuffer(decoded, 10000);
                if (output >= 0) {
                    android.media.Image image = verifier.getOutputImage(output);
                    android.media.Image.Plane plane = image.getPlanes()[0];
                    int y = plane.getBuffer().get(plane.getBuffer().position()
                            + (image.getCropRect().top + 32) * plane.getRowStride()
                            + (image.getCropRect().left + 32) * plane.getPixelStride()) & 255;
                    verifier.releaseOutputBuffer(output, false);
                    require(y > 140, "Encoded fixture must contain nonblack luma, actual=" + y);
                    verified = true;
                }
            }
            require(verified, "Fixture must decode through a real MediaCodec before surface testing");
        } finally {
            verifier.release();
        }
        }
        java.util.concurrent.atomic.AtomicReference<String> error = new java.util.concurrent.atomic.AtomicReference<>();
        H264SurfaceRenderer renderer = new H264SurfaceRenderer(activity, new H264SurfaceRenderer.Listener() {
            public void onDecoderError(String message) { error.set(message); }
        });
        try {
            runOnMainSync(() -> {
                renderer.setDecoderMode(mode);
                android.widget.FrameLayout host = new android.widget.FrameLayout(activity);
                host.addView(renderer, new android.widget.FrameLayout.LayoutParams(64, 64));
                activity.setContentView(host);
                if (mode == 3) {
                    View original = renderer.getChildAt(0);
                    renderer.setTextureRendering(true);
                    require(original == renderer.getChildAt(0) && original instanceof OpenH264GlView,
                            "System Texture preference must not replace original GLES2 rendering");
                }
            });
            waitForIdleSync();
            long deadline = SystemClock.elapsedRealtime() + 5000;
            android.view.SurfaceView surface = (android.view.SurfaceView) renderer.getChildAt(0);
            while (!surface.getHolder().getSurface().isValid() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(20);
            }
            require(surface.getHolder().getSurface().isValid(), "Video test needs a live SurfaceView");
            // Exceed the queue's capacity deliberately, then verify actual decoded pixels.
            Field queueField = H264SurfaceRenderer.class.getDeclaredField("frameQueue");
            queueField.setAccessible(true);
            VideoFrameQueue queue = (VideoFrameQueue) queueField.get(renderer);
            synchronized (queue) {
                long generation = queue.generation();
                for (int i = 0; i < 100; i++) renderer.submitFrame(frame);
                require(queue.generation() > generation, "Pixel test must actually force queue overflow");
            }
            SystemClock.sleep(250);
            boolean rendered = false;
            int lastCopyResult = -1;
            int lastColor = 0;
            deadline = SystemClock.elapsedRealtime() + 5000;
            while (!rendered && error.get() == null && SystemClock.elapsedRealtime() < deadline) {
                // Keep feeding the stream: codecs may buffer several inputs before first output.
                renderer.submitFrame(nextFrame);
                android.graphics.Bitmap pixel = android.graphics.Bitmap.createBitmap(
                        16, 16, android.graphics.Bitmap.Config.ARGB_8888);
                CountDownLatch copied = new CountDownLatch(1);
                java.util.concurrent.atomic.AtomicInteger result = new java.util.concurrent.atomic.AtomicInteger(-1);
                try {
                    android.view.PixelCopy.request(surface, pixel, code -> {
                        result.set(code);
                        copied.countDown();
                    }, new android.os.Handler(android.os.Looper.getMainLooper()));
                    require(copied.await(2, TimeUnit.SECONDS), "Decoded SurfaceView copy must complete");
                    lastCopyResult = result.get();
                    if (result.get() == android.view.PixelCopy.SUCCESS) {
                        int color = pixel.getPixel(8, 8);
                        lastColor = color;
                        rendered = android.graphics.Color.red(color) > 140
                                && Math.abs(android.graphics.Color.red(color) - android.graphics.Color.green(color)) < 15
                                && Math.abs(android.graphics.Color.red(color) - android.graphics.Color.blue(color)) < 15;
                    }
                } finally {
                    pixel.recycle();
                }
                if (!rendered) SystemClock.sleep(60);
            }
            require(error.get() == null && rendered,
                    "SurfaceView must render neutral pixels after compressed queue overflow: " + error.get()
                            + " copy=" + lastCopyResult + " pixel=" + Integer.toHexString(lastColor));
        } finally {
            renderer.close();
            runOnMainSync(activity::showMain);
        }
    }

    private void testRotationLayout(Activity activity) throws Exception {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        boolean hadScaling = prefs.contains("video_scale_mode");
        int scaling = prefs.getInt("video_scale_mode", 0);
        prefs.edit().putInt("video_scale_mode", 0).commit();
        try {
            for (int orientation : new int[]{ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                runOnMainSync(() -> activity.setRequestedOrientation(orientation));
                int expected = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
                long deadline = SystemClock.elapsedRealtime() + 5000;
                while (activity.getResources().getConfiguration().orientation != expected
                        && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                waitForIdleSync();
                // Window relayout/inset dispatch arrives after the configuration callback.
                SystemClock.sleep(350);
                waitForIdleSync();
                require(activity.getResources().getConfiguration().orientation == expected,
                        "Display rotation must complete");
                View center = (View) activityField(activity, "stageCenter");
                View prompt = (View) activityField(activity, "stagePromptCard");
                Rect centerBounds = new Rect();
                Rect promptBounds = new Rect();
                runOnMainSync(() -> {
                    center.getGlobalVisibleRect(centerBounds);
                    prompt.getGlobalVisibleRect(promptBounds);
                });
                require(!centerBounds.isEmpty() && !promptBounds.isEmpty(),
                        "Home logo and prompt must be visible");
                require(!Rect.intersects(centerBounds, promptBounds),
                        "Home logo and prompt must not overlap");
                require(centerBounds.height() == center.getHeight(), "Home logo must not be clipped");
                require(promptBounds.height() == prompt.getHeight(), "Home prompt must not be clipped");
                Method requested = MainActivity.class.getDeclaredMethod("getRequestedVideoSize");
                requested.setAccessible(true);
                int[] canvas = (int[]) requested.invoke(activity);
                require(canvas[0] >= canvas[1], "Session canvas must be landscape in either orientation");
            }

            Method projection = MainActivity.class.getDeclaredMethod("setProjectionMode", boolean.class);
            projection.setAccessible(true);
            int[] homeBeforeProjection = homeGeometry(activity);
            for (int[] stream : new int[][]{{1280, 720}, {1080, 2350}, {1920, 1080}}) {
                runOnMainSync(() -> {
                    try {
                        setActivityField(activity, "lastVideoWidth", stream[0]);
                        setActivityField(activity, "lastVideoHeight", stream[1]);
                        projection.invoke(activity, true);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });
                waitForIdleSync();
                SystemClock.sleep(200);
                waitForIdleSync();
                View video = (View) activityField(activity, "videoRenderer");
                View root = (View) activityField(activity, "rootPage");
                require(video.getWidth() <= root.getWidth() && video.getHeight() <= root.getHeight(),
                        "Video must fit inside the rotated viewport");
                require(Math.abs(video.getWidth() / (double) video.getHeight()
                                - stream[0] / (double) stream[1]) < 0.005,
                        "Actual rendered view must preserve stream aspect ratio");
                int[] rotations = stream[0] == 1280 ? new int[0]
                        : new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE};
                for (int rotation : rotations) {
                    runOnMainSync(() -> activity.setRequestedOrientation(rotation));
                    int expected = rotation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
                    long deadline = SystemClock.elapsedRealtime() + 5000;
                    while (activity.getResources().getConfiguration().orientation != expected
                            && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                    SystemClock.sleep(350);
                    waitForIdleSync();
                    require(activity.getResources().getConfiguration().orientation == expected,
                            "Active projection rotation must complete");
                    require(video == activityField(activity, "videoRenderer"),
                            "Rotation must retain the existing projection renderer");
                    require(Math.abs(video.getWidth() / (double) video.getHeight()
                                    - stream[0] / (double) stream[1]) < 0.005,
                            "Active rotation must refit video without distortion");
                }
                runOnMainSync(() -> {
                    try {
                        projection.invoke(activity, false);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });
                SystemClock.sleep(350);
                waitForIdleSync();
                int[] homeAfterProjection = homeGeometry(activity);
                require(java.util.Arrays.equals(homeBeforeProjection, homeAfterProjection),
                        "Home geometry must be restored after projection: before="
                                + java.util.Arrays.toString(homeBeforeProjection) + ", after="
                                + java.util.Arrays.toString(homeAfterProjection));
            }
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            if (hadScaling) editor.putInt("video_scale_mode", scaling);
            else editor.remove("video_scale_mode");
            editor.commit();
        }
    }

    private int[] homeGeometry(Activity activity) throws Exception {
        View root = (View) activityField(activity, "rootPage");
        View center = (View) activityField(activity, "stageCenter");
        View logo = (View) activityField(activity, "stageLogo");
        int[] geometry = new int[12];
        runOnMainSync(() -> {
            root.getLocationOnScreen(geometry);
            geometry[2] = root.getWidth();
            geometry[3] = root.getHeight();
            geometry[4] = root.getPaddingLeft();
            geometry[5] = root.getPaddingTop();
            geometry[6] = root.getPaddingRight();
            geometry[7] = root.getPaddingBottom();
            int[] location = new int[2];
            center.getLocationOnScreen(location);
            geometry[8] = location[0];
            geometry[9] = location[1];
            geometry[10] = logo.getWidth();
            geometry[11] = logo.getHeight();
        });
        return geometry;
    }

    private void bindObserver() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        observer = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                observed = ((ConnectionService.LocalBinder) binder).getService();
                ready.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        observerBound = getTargetContext().bindService(new Intent(getTargetContext(), ConnectionService.class),
                observer, Context.BIND_AUTO_CREATE);
        require(observerBound && ready.await(5, TimeUnit.SECONDS), "Service binding must complete");
    }

    private int countViews(View view, Class<?> type) {
        int count = type.isInstance(view) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countViews(group.getChildAt(i), type);
            }
        }
        return count;
    }

    private void testHomeConnectionStates(Activity activity) throws Exception {
        MainActivity main = (MainActivity) activity;
        ViewGroup root = (ViewGroup) activityField(activity, "rootPage");
        ViewGroup header = (ViewGroup) ((View) activityField(activity, "stagePromptCard")).getParent();
        require(header.getChildCount() == 2, "Home header must contain status and settings");
        View settings = header.getChildAt(1);
        require(settings.isClickable() && "设置".contentEquals(settings.getContentDescription()),
                "Single home action must open settings");
        require(settings.getRight() == header.getWidth() - header.getPaddingRight(),
                "Settings button must be right aligned");
        require(countViews(root, Widgets.LinkIcon.class) == 1,
                "Home must not contain Bluetooth/Wi-Fi icons");
        TextView box = (TextView) activityField(activity, "connectionStatus");
        TextView phone = (TextView) activityField(activity, "phoneStatus");
        TextView prompt = (TextView) activityField(activity, "stagePrompt");
        View spinner = (View) activityField(activity, "stageSpinner");
        View center = (View) activityField(activity, "stageCenter");
        ViewGroup stage = (ViewGroup) center.getParent();
        require(Math.abs(center.getLeft() + center.getWidth() / 2f - stage.getWidth() / 2f) <= 1
                        && Math.abs(center.getTop() + center.getHeight() / 2f - stage.getHeight() / 2f) <= 1,
                "Logo and title group must be centered independently of header");
        View logo = (View) activityField(activity, "stageLogo");
        String[][] cases = {
            {"盒子：未连接", "手机：未连接", "请将设备插入车机的USB口", "false"},
            {"盒子：USB 通道已建立", "手机：等待盒子状态", "连接中，请稍等...", "true"},
            {"盒子：协议握手完成", "手机：未连接", "正在查找，请打开手机蓝牙与Wi-Fi", "false"},
            {"盒子：协议握手完成", "手机：已检测到 CarPlay", "连接中，请稍等...", "true"},
            {"盒子：协议握手完成", "手机：没有已配对手机，自动连接不可用", "正在查找，请打开手机蓝牙与Wi-Fi", "false"},
            {"盒子：协议握手完成", "手机：连接失败：车机 Wi-Fi 异常", "手机连接失败，请检查蓝牙与Wi-Fi后重试", "false"},
            {"盒子：协议通信失败：test", "手机：未连接", "设备连接失败，请重新插拔设备", "false"},
        };
        Field plugged = MainActivity.class.getDeclaredField("phonePlugged");
        plugged.setAccessible(true);
        for (int i = 0; i < cases.length; i++) {
            String[] test = cases[i];
            boolean isPhonePlugged = i == 3;
            runOnMainSync(() -> {
                try {
                    plugged.setBoolean(activity, isPhonePlugged);
                } catch (IllegalAccessException exception) {
                    throw new AssertionError(exception);
                }
                box.setText(test[0]);
                phone.setText(test[1]);
            });
            waitForIdleSync();
            require(test[2].contentEquals(prompt.getText()), "Home must use expected concise status: " + test[2]);
            require((spinner.getVisibility() == View.VISIBLE) == Boolean.parseBoolean(test[3]),
                    "Only connecting status may show spinner");
            require(center.isShown() && logo.getScaleX() == 1f && logo.getScaleY() == 1f,
                    "Connecting must retain a static logo and title");
            if (spinner.isShown()) {
                float density = activity.getResources().getDisplayMetrics().density;
                require(spinner.getWidth() <= 20 * density && spinner.getHeight() <= 20 * density,
                        "Spinner must remain small");
                require(spinner.getParent() == prompt.getParent() && spinner.getLeft() >= prompt.getRight(),
                        "Spinner must follow the prompt text in the same row");
            }
        }
        String[] labels = {"未插入", "查找手机", "连接中", "未配对", "连接失败", "恢复真实状态"};
        for (String label : labels) {
            require(findPreviewButton(root, label) == null,
                    "Temporary preview button must be removed: " + label);
        }
        runOnMainSync(main::showSettings);
        waitForIdleSync();
        SettingsScreens screens = (SettingsScreens) activityField(activity, "screens");
        Field overlayField = SettingsScreens.class.getDeclaredField("overlayPermissionStatus");
        overlayField.setAccessible(true);
        TextView overlayStatus = (TextView) overlayField.get(screens);
        require((android.provider.Settings.canDrawOverlays(main) ? "已允许" : "未允许")
                        .contentEquals(overlayStatus.getText()),
                "Overlay entry must reflect actual system authorization");
        android.content.IntentFilter overlayFilter =
                new android.content.IntentFilter(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        overlayFilter.addDataScheme("package");
        ActivityMonitor overlayMonitor = addMonitor(overlayFilter,
                new ActivityResult(Activity.RESULT_CANCELED, null), true);
        try {
            runOnMainSync(main::openOverlayPermission);
            require(overlayMonitor.getHits() == 1,
                    "Overlay entry must launch system overlay permission settings");
            runOnMainSync(() -> {
                overlayStatus.setText("stale");
                screens.updateOverlayPermissionStatus();
            });
            require((android.provider.Settings.canDrawOverlays(main) ? "已允许" : "未允许")
                            .contentEquals(overlayStatus.getText()),
                    "Overlay status must refresh from system state");
        } finally {
            removeMonitor(overlayMonitor);
        }
        Field bluetoothField = SettingsScreens.class.getDeclaredField("bluetoothStatusText");
        Field wifiField = SettingsScreens.class.getDeclaredField("wifiStatusText");
        bluetoothField.setAccessible(true);
        wifiField.setAccessible(true);
        TextView bluetooth = (TextView) bluetoothField.get(screens);
        TextView wifi = (TextView) wifiField.get(screens);
        runOnMainSync(() -> screens.updateConnectionStatus(true, false));
        require("已连接".contentEquals(bluetooth.getText()) && "未连接".contentEquals(wifi.getText()),
                "Settings must display independent radio link states");
        runOnMainSync(() -> screens.updateConnectionStatus(false, true));
        require("未连接".contentEquals(bluetooth.getText()) && "已连接".contentEquals(wifi.getText()),
                "Settings radio states must update while visible");
        runOnMainSync(() -> {
            box.setText("盒子：未连接");
            phone.setText("手机：未连接");
            main.showMain();
        });
        waitForIdleSync();
    }

    private boolean containsText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    private View findPreviewButton(View view, String text) {
        if (view instanceof android.widget.Button
                && text.contentEquals(((TextView) view).getText())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findPreviewButton(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void testSteeringLearning(MainActivity main) throws Exception {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        boolean hadProtocol = prefs.contains(SteeringProtocol.PREFERENCE);
        int previousProtocol = prefs.getInt(SteeringProtocol.PREFERENCE, 0);
        String key = "steering_cs35plus_key_" + android.view.KeyEvent.KEYCODE_F8;
        boolean existed = prefs.contains(key);
        int previous = prefs.getInt(key, -1);
        try {
            testSteeringProtocolMigration();
            prefs.edit().putInt(SteeringProtocol.PREFERENCE, 1).commit();
            runOnMainSync(main::showSteeringLearning);
            waitForIdleSync();
            SettingsScreens screens = (SettingsScreens) activityField(main, "screens");
            Field logField = SettingsScreens.class.getDeclaredField("steeringLog");
            logField.setAccessible(true);
            TextView log = (TextView) logField.get(screens);
            java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
            getTargetContext().sendOrderedBroadcast(new Intent(MainActivity.VENDOR_KEY_ACTION)
                            .setPackage(getTargetContext().getPackageName())
                            .putExtra("Key_code", "NEXT").putExtra("Key_state", "UP"),
                    null, new android.content.BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent intent) {
                            delivered.countDown();
                        }
                    }, new android.os.Handler(android.os.Looper.getMainLooper()), 0, null, null);
            require(delivered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Vendor diagnostic broadcast must finish delivery");
            waitForIdleSync();
            require(log.getText().toString().contains("[厂家广播]")
                            && log.getText().toString().contains("Key_code=NEXT")
                            && log.getText().toString().contains("Key_state=UP"),
                    "Vendor broadcast must appear in on-page log with original string fields");
            require(log.getText().toString().contains("广播类型：有序广播")
                            && log.getText().toString().contains("禁止中止标记：否")
                            && log.getText().toString().contains("当前行为：仅检测，不拦截"),
                    "Ordered broadcast must expose its real delivery type without claiming interception");
            getTargetContext().sendBroadcast(new Intent(MainActivity.VENDOR_KEY_ACTION)
                    .setPackage(getTargetContext().getPackageName())
                    .putExtra("Key_code", "DIAGNOSTIC").putExtra("Key_state", "NONE"));
            long diagnosticDeadline = SystemClock.elapsedRealtime() + 3000;
            while (!log.getText().toString().contains("广播类型：普通广播")
                    && SystemClock.elapsedRealtime() < diagnosticDeadline) {
                SystemClock.sleep(20);
                waitForIdleSync();
            }
            require(log.getText().toString().contains("广播类型：普通广播"),
                    "Unordered vendor broadcast must be identified as ordinary");
            CountDownLatch noAbortDelivered = new CountDownLatch(1);
            getTargetContext().sendOrderedBroadcast(new Intent(MainActivity.VENDOR_KEY_ACTION)
                            .setPackage(getTargetContext().getPackageName())
                            .addFlags(Intent.FLAG_RECEIVER_NO_ABORT)
                            .putExtra("Key_code", "DIAGNOSTIC").putExtra("Key_state", "NONE"),
                    null, new android.content.BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent intent) {
                            noAbortDelivered.countDown();
                        }
                    }, new android.os.Handler(android.os.Looper.getMainLooper()), 0, null, null);
            require(noAbortDelivered.await(3, TimeUnit.SECONDS),
                    "Diagnostic receiver must preserve ordered delivery to subsequent receivers");
            waitForIdleSync();
            require(log.getText().toString().contains("禁止中止标记：是"),
                    "Sender NO_ABORT flag must be exposed without asserting interceptability");
            require(prefs.getInt(key, -1) == previous,
                    "Vendor diagnostic broadcast must not change key bindings");
            runOnMainSync(() -> {
                main.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_F8));
                main.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_F8));
            });
            require(log.getText().toString().contains("KEYCODE_F8")
                            && log.getText().toString().contains("按下")
                            && log.getText().toString().contains("松开"),
                    "On-page log must detect press/release without learning");
            require(prefs.getInt(key, -1) == previous,
                    "Passive logging must not change learned bindings");
            Field field = SettingsScreens.class.getDeclaredField("steeringBindings");
            field.setAccessible(true);
            TextView[] bindings = (TextView[]) field.get(screens);
            require(bindings.length == 6 && bindings[0].isShown(),
                    "Steering learning must open a dedicated page with six functions");
            String[] vendorCodes = {"NEXT", "PRE", "VR", "MAIN"};
            java.util.Map<String, ?> savedVendor = prefs.getAll();
            try {
                for (String code : vendorCodes) {
                    prefs.edit().remove(SteeringProtocol.vendorPrefix() + code).commit();
                    runOnMainSync(() -> ((View) bindings[3].getParent().getParent()).performClick());
                    sendVendorBroadcast(code, "UP");
                    require(!prefs.contains(SteeringProtocol.vendorPrefix() + code), "Orphan UP must not learn");
                    sendVendorBroadcast(code, "DOWN");
                    if (!"MAIN".equals(code)) {
                        require(!prefs.contains(SteeringProtocol.vendorPrefix() + code), "Physical key needs release");
                        sendVendorBroadcast(code, "UP");
                    }
                    require(prefs.getInt(SteeringProtocol.vendorPrefix() + code, -1) == 10003,
                            "Vendor key must bind through broadcast: " + code);
                    require(bindings[3].getText().toString().contains("厂家 " + code),
                            "Binding must identify vendor source");
                }
                runOnMainSync(() -> ((View) bindings[0].getParent().getParent()).performClick());
                sendVendorBroadcast("VR", "LONG_EVENT");
                require(prefs.getInt(SteeringProtocol.vendorPrefix() + "VR", -1) == 10003, "Long state must not rebind");
                runOnMainSync(screens::cancelSteeringLearning);
            } finally {
                SharedPreferences.Editor editor = prefs.edit();
                for (String code : vendorCodes) {
                    String vendorKey = SteeringProtocol.vendorPrefix() + code;
                    Object value = savedVendor.get(vendorKey);
                    if (value instanceof Integer) editor.putInt(vendorKey, (Integer) value);
                    else editor.remove(vendorKey);
                }
                editor.commit();
            }
            View firstTile = (View) bindings[0].getParent().getParent();
            View secondTile = (View) bindings[1].getParent().getParent();
            require(firstTile.getParent() == secondTile.getParent()
                            && secondTile.getLeft() >= firstTile.getRight(),
                    "Learning actions must be tiled side by side, not a vertical list");
            int[] actions = {10001, 10002, 203, 10003, 200, 106};
            int[][] idle = {{205}, {204}, {203}, {5, 6}, {200}, {106}};
            for (int i = 0; i < actions.length; i++) {
                require(java.util.Arrays.equals(BoxProtocolSession.steeringCommands(actions[i], 0), idle[i]),
                        "Steering action must map to original box commands: " + actions[i]);
            }
            require(java.util.Arrays.equals(BoxProtocolSession.steeringCommands(10001, 2), new int[]{300}),
                    "Incoming-call previous action must answer instead of skipping");
            require(BoxProtocolSession.steeringCommands(10001, 1).length == 0,
                    "Answer button must not skip music during an active call");
            require(java.util.Arrays.equals(BoxProtocolSession.steeringCommands(10002, 2), new int[]{301})
                            && java.util.Arrays.equals(BoxProtocolSession.steeringCommands(10002, 1), new int[]{301}),
                    "Next action must hang up during incoming and active calls");
            runOnMainSync(() -> {
                ((View) bindings[0].getParent().getParent()).performClick();
                main.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_F8));
                main.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_F8));
            });
            require(prefs.getInt(key, -1) == 10001 && bindings[0].getText().toString().contains("KEYCODE_F8"),
                    "Page must learn key release and show persisted binding");
            runOnMainSync(() -> {
                ((View) bindings[1].getParent().getParent()).performClick();
                main.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_F8));
                main.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_F8));
            });
            require(prefs.getInt(key, -1) == 10002
                            && !bindings[0].getText().toString().contains("KEYCODE_F8")
                            && bindings[1].getText().toString().contains("KEYCODE_F8"),
                    "Relearning a key must move its binding and refresh both rows");
            runOnMainSync(() -> {
                ((View) bindings[0].getParent().getParent()).performClick();
                screens.cancelSteeringLearning();
                screens.handleSteeringKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_F8));
                screens.handleSteeringKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_F8));
            });
            require(prefs.getInt(key, -1) == 10002, "Cancelled learning must not modify binding");
            runOnMainSync(() -> {
                for (int i = 0; i < 60; i++) {
                    main.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                            android.view.KeyEvent.KEYCODE_F8));
                }
            });
            require(log.getText().toString().split("键码=", -1).length - 1 == 50,
                    "On-page log must retain exactly the newest 50 events");
            View clearLog = findPreviewButton(main.getWindow().getDecorView(), "清除日志");
            require(clearLog != null, "Log must provide a clear button");
            runOnMainSync(() -> clearLog.performClick());
            require("等待按键事件...".contentEquals(log.getText())
                            && prefs.getInt(key, -1) == 10002,
                    "Clearing log must reset display without changing bindings");
            runOnMainSync(() -> main.dispatchKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_F8)));
            require(log.getText().toString().split("键码=", -1).length - 1 == 1,
                    "Logging must resume with only new events after clearing");
            runOnMainSync(() -> {
                ((View) bindings[0].getParent().getParent()).performClick();
                main.showSettings();
            });
            require(!screens.handleSteeringKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_F8)), "Leaving page must stop capturing keys");
            runOnMainSync(() -> {
                main.showSteeringLearning();
                ((View) bindings[0].getParent().getParent()).performClick();
                prefs.edit().putInt(SteeringProtocol.PREFERENCE, 0).commit();
                main.onSteeringProtocolChanged();
                require(!screens.handleSteeringKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                                android.view.KeyEvent.KEYCODE_F8)),
                        "Changing protocols must cancel learning");
                require(containsText(main.getWindow().getDecorView(), "方控协议"),
                        "Learning page must expose protocol selection");
                require(!containsText(main.getWindow().getDecorView(), "已绑定：KEYCODE_F8"),
                        "Default protocol must not display CS35Plus bindings");
            });
            sendVendorBroadcast("NEXT", "DOWN");
            TextView currentLog = (TextView) logField.get(screens);
            require("等待按键事件...".contentEquals(currentLog.getText()),
                    "Default protocol must ignore vendor broadcasts and reset its log");
            runOnMainSync(() -> {
                prefs.edit().putInt(SteeringProtocol.PREFERENCE, 1).commit();
                main.onSteeringProtocolChanged();
                require(observed.mediaCommand(android.view.KeyEvent.KEYCODE_F8) == 10002,
                        "Media-session lookup must use the selected protocol bindings");
            });
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            if (existed) editor.putInt(key, previous); else editor.remove(key);
            if (hadProtocol) editor.putInt(SteeringProtocol.PREFERENCE, previousProtocol);
            else editor.remove(SteeringProtocol.PREFERENCE);
            editor.commit();
            runOnMainSync(main::showMain);
        }
    }

    private void testRootUsbAuthorization(MainActivity activity) throws Exception {
        require("未授权".equals(RootUsbAuthorization.rootStatus()),
                "Root status must show the concise unauthorized label before verification");
        require(RootUsbAuthorization.collectResult(new FakeRootProcess(0,
                        "uid=0(root) gid=0(root) groups=0(root)"), 1000).isRoot(),
                "Successful id output must confirm real UID 0");
        for (String output : new String[]{"", "uid=10000(u0_a0) gid=10000", "message uid=0(root)"}) {
            require(!RootUsbAuthorization.collectResult(new FakeRootProcess(0, output), 1000).isRoot(),
                    "Zero exit or an embedded UID string alone must not prove root: " + output);
        }
        require(!RootUsbAuthorization.collectResult(new FakeRootProcess(1, "uid=0(root)"), 1000).isRoot(),
                "Failed root command must not be reported as granted");
        require("'a'\\''b; $(id)'".equals(RootUsbAuthorization.quote("a'b; $(id)")),
                "Root command arguments must be single-quoted without shell interpolation");
        SharedPreferences prefs = getTargetContext().getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        boolean hadMode = prefs.contains("usb_transport");
        int previous = prefs.getInt("usb_transport", 0);
        try {
            runOnMainSync(() -> {
                activity.showMore();
                require(!containsText(activity.getWindow().getDecorView(), "高级功能")
                                && !containsText(activity.getWindow().getDecorView(), "申请 Root 权限"),
                        "Advanced features must be hidden before version taps");
                activity.showAdvanced();
                require(!containsText(activity.getWindow().getDecorView(), "申请 Root 权限"),
                        "Locked advanced navigation must not bypass version taps");
                activity.showSettings();
                for (int i = 0; i < 3; i++) activity.onVersionTapped();
                require(!activity.advancedFeaturesUnlocked(),
                        "Three version taps must not unlock advanced features");
                activity.onVersionTapped();
                require(activity.advancedFeaturesUnlocked(),
                        "Four consecutive version taps must unlock advanced features");
                activity.showMore();
                require(containsText(activity.getWindow().getDecorView(), "高级功能"),
                        "More settings must offer advanced navigation after unlocking");
                activity.showAdvanced();
                require(containsText(activity.getWindow().getDecorView(), RootUsbAuthorization.rootStatus())
                                && containsText(activity.getWindow().getDecorView(), "USB 权限状态"),
                        "Advanced page must show root and USB status without requesting root");
                activity.onBackPressed();
                require(!containsText(activity.getWindow().getDecorView(), "申请 Root 权限"),
                        "Back from advanced must return to More settings");
                activity.showAdvanced();
                require(containsText(activity.getWindow().getDecorView(), "申请 Root 权限"),
                        "Advanced settings must offer root request independently of USB");
                require(!containsText(activity.getWindow().getDecorView(), "Root USB 授权（实验）")
                                && !containsText(activity.getWindow().getDecorView(), "转为系统应用"),
                        "Removed privileged actions must not appear even after unlocking");
                activity.showMain();
            });
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            if (hadMode) editor.putInt("usb_transport", previous); else editor.remove("usb_transport");
            editor.commit();
        }
        FakeRootProcess denied = new FakeRootProcess(1, "permission denied");
        String deniedResult = RootUsbAuthorization.collect(denied, 1000);
        require(deniedResult.contains("退出码：1") && deniedResult.contains("permission denied")
                        && denied.destroyed,
                "Root denial must retain exit code/output and release the process");
        FakeRootProcess noGrant = new FakeRootProcess(0, "");
        require(RootUsbAuthorization.collect(noGrant, 1000).contains("无输出"),
                "Zero exit without output must not be reported as USB success");
        FakeRootProcess timeout = new FakeRootProcess(0, "");
        timeout.running = true;
        require(RootUsbAuthorization.collect(timeout, 20).contains("超时") && timeout.destroyed,
                "Pending root approval must time out and destroy its process");
        char[] largeOutput = new char[10000];
        java.util.Arrays.fill(largeOutput, 'x');
        String bounded = RootUsbAuthorization.collect(new FakeRootProcess(1, new String(largeOutput)), 1000);
        require(bounded.length() < 4200, "Root output capture must remain bounded");
        ProcessBuilder unprivileged = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                RootUsbGrant.class.getName(), "10000", "/dev/bus/usb/001/001", "4884", "5408", "0")
                .redirectErrorStream(true);
        unprivileged.environment().put("CLASSPATH", getTargetContext().getApplicationInfo().sourceDir);
        String rejected = RootUsbAuthorization.collect(unprivileged.start(), 5000);
        require(rejected.contains("退出码：1") && rejected.contains("su did not provide UID 0"),
                "Real app_process helper must load and reject non-root execution: " + rejected);
    }

    private void testSystemAppConversion() throws Exception {
        testSystemLibraryPlan();
        require(SystemAppInstaller.supported(19) && SystemAppInstaller.supported(20)
                        && !SystemAppInstaller.supported(18) && !SystemAppInstaller.supported(21)
                        && !SystemAppInstaller.supported(Build.VERSION.SDK_INT),
                "System conversion must be strictly limited to KitKat");
        android.content.pm.ApplicationInfo info = getTargetContext().getApplicationInfo();
        String command = SystemAppInstaller.command(info, "check");
        require(command.contains("SystemAppInstaller check " + info.uid)
                        && !command.contains("chmod") && !command.contains("remount"),
                "Inspection must launch the fixed helper rather than shell mutations");
        try {
            SystemAppInstaller.command(info, "install; id");
            throw new AssertionError("System conversion must not accept arbitrary actions");
        } catch (IllegalArgumentException expected) {
            assertions++;
        }
        java.io.File source = java.io.File.createTempFile("system-app-source-", ".apk",
                getTargetContext().getCacheDir());
        java.io.File copy = new java.io.File(source.getPath() + ".copy");
        try {
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(source)) {
                output.write(new byte[]{1, 2, 3, 4, 5});
            }
            SystemAppInstaller.copyNew(source, copy);
            String original = SystemAppInstaller.digest(source);
            require(original.equals(SystemAppInstaller.digest(copy)),
                    "System APK copy must verify its SHA-256 against the source");
            try {
                SystemAppInstaller.copyNew(source, copy);
                throw new AssertionError("System copy must never overwrite an existing file");
            } catch (java.io.IOException expected) {
                require(original.equals(SystemAppInstaller.digest(copy)),
                        "Refused overwrite must preserve existing data");
            }
        } finally {
            require(!copy.exists() || copy.delete(), "Remove only the named test copy");
            require(source.delete(), "Remove only the named test source");
        }
        ProcessBuilder unsupported = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                SystemAppInstaller.class.getName(), "install", Integer.toString(info.uid), "0")
                .redirectErrorStream(true);
        unsupported.environment().put("CLASSPATH", info.sourceDir);
        String rejected = RootUsbAuthorization.collect(unsupported.start(), 5000);
        require(rejected.contains("退出码：1") && rejected.contains("仅支持 Android 4.4"),
                "Real helper must refuse unsupported devices before any system write: " + rejected);
    }

    private void testSystemLibraryPlan() throws Exception {
        java.io.File root = new java.io.File(getTargetContext().getCacheDir(),
                "system-library-test-" + System.nanoTime()).getCanonicalFile();
        java.io.File source = new java.io.File(root, "source");
        java.io.File system = new java.io.File(root, "system");
        require(source.mkdirs() && system.mkdir(), "Create isolated library test directories");
        String[] names = {"libnx1.so", "libconnection.so", "libopenH264decoder.so"};
        try {
            for (String name : names) {
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(source, name))) {
                    output.write((name + "-original").getBytes("UTF-8"));
                }
            }
            SystemAppInstaller.LibraryInstall[] plan = SystemAppInstaller.planLibraries(source, system);
            require(plan.length == 3 && plan[0].create && plan[1].create && plan[2].create
                            && !plan[0].target.exists() && !plan[1].target.exists() && !plan[2].target.exists(),
                    "Missing libraries must be planned for copying without read-only preflight mutations");
            for (SystemAppInstaller.LibraryInstall library : plan) {
                SystemAppInstaller.copyNew(library.source, library.stage);
                require(library.hash.equals(SystemAppInstaller.digest(library.stage)),
                        "Staged library must match its planned SHA-256");
                require(library.stage.renameTo(library.target), "Publish only the isolated test library");
            }
            plan = SystemAppInstaller.planLibraries(source, system);
            require(!plan[0].create && !plan[1].create && !plan[2].create,
                    "Identical pre-existing libraries must be reused without ownership");
            String sourceHash = SystemAppInstaller.digest(plan[0].source);
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(plan[0].target)) {
                output.write(new byte[]{9, 8, 7});
            }
            String conflictHash = SystemAppInstaller.digest(plan[0].target);
            try {
                SystemAppInstaller.planLibraries(source, system);
                throw new AssertionError("Different system libraries must block conversion");
            } catch (java.io.IOException expected) {
                require(expected.getMessage().contains("拒绝覆盖")
                                && conflictHash.equals(SystemAppInstaller.digest(plan[0].target))
                                && sourceHash.equals(SystemAppInstaller.digest(plan[0].source)),
                        "Conflict must preserve both original and existing system library");
            }
            require(plan[0].target.delete(), "Remove isolated conflicting fixture");
            require(plan[0].stage.createNewFile(), "Create isolated interrupted-install fixture");
            try {
                SystemAppInstaller.planLibraries(source, system);
                throw new AssertionError("Existing library staging must block installation");
            } catch (java.io.IOException expected) {
                require(expected.getMessage().contains("暂存文件已存在"),
                        "Interrupted install must remain visible instead of being silently overwritten");
            }
        } finally {
            for (String name : names) {
                java.io.File[] files = {
                        new java.io.File(source, name), new java.io.File(system, name),
                        new java.io.File(system, ".PlayAutoReplacement." + name + ".installing")
                };
                for (java.io.File file : files) {
                    require(!file.exists() || file.delete(), "Clean up only the named library fixture: " + file);
                }
            }
            require(source.delete() && system.delete() && root.delete(),
                    "Remove empty isolated library test directories");
        }
    }

    private static final class FakeRootProcess extends java.lang.Process {
        final int code;
        final java.io.InputStream input;
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        boolean running;
        boolean destroyed;
        FakeRootProcess(int code, String text) throws java.io.UnsupportedEncodingException {
            this.code = code;
            input = new java.io.ByteArrayInputStream(text.getBytes("UTF-8"));
        }
        @Override public java.io.OutputStream getOutputStream() { return output; }
        @Override public java.io.InputStream getInputStream() { return input; }
        @Override public java.io.InputStream getErrorStream() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        @Override public int waitFor() { return code; }
        @Override public int exitValue() {
            if (running) throw new IllegalThreadStateException("still awaiting approval");
            return code;
        }
        @Override public void destroy() { destroyed = true; running = false; }
    }

    private void testSteeringProtocolMigration() {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("steering_protocol_test", Context.MODE_PRIVATE);
        String androidKey = "steering_key_138";
        String vendorKey = "steering_vendor_NEXT";
        String migratedKey = SteeringProtocol.vendorPrefix() + "NEXT";
        try {
            prefs.edit().remove("steering_protocol_migrated").remove(SteeringProtocol.PREFERENCE)
                    .remove("steering_cs35plus_key_138").remove(migratedKey)
                    .putInt(androidKey, 10001).putInt(vendorKey, 10003).commit();
            SteeringProtocol.migrate(prefs);
            require(!SteeringProtocol.vendorEnabled(prefs)
                            && SteeringProtocol.androidCommand(prefs, 138) == 10001
                            && SteeringProtocol.vendorCommand(prefs, "NEXT") == -1,
                    "Default protocol must preserve Android bindings but disable vendor execution");
            require(!prefs.contains(vendorKey) && prefs.getInt(migratedKey, -1) == 10003,
                    "Legacy vendor bindings must migrate to CS35Plus");
            prefs.edit().putInt(SteeringProtocol.PREFERENCE, 1).commit();
            require(SteeringProtocol.androidCommand(prefs, 138) == -1
                            && SteeringProtocol.vendorCommand(prefs, "NEXT") == 10003,
                    "CS35Plus must have independent Android bindings and migrated vendor bindings");
            prefs.edit().putInt("steering_cs35plus_key_138", 10002).putInt(migratedKey, 203).commit();
            SteeringProtocol.migrate(prefs);
            require(SteeringProtocol.androidCommand(prefs, 138) == 10002
                            && SteeringProtocol.vendorCommand(prefs, "NEXT") == 203,
                    "Migration must not overwrite subsequently learned bindings");
            prefs.edit().putInt(SteeringProtocol.PREFERENCE, 0).commit();
            require(SteeringProtocol.androidCommand(prefs, 138) == 10001,
                    "Switching back must restore the default Android binding");
        } finally {
            prefs.edit().remove(androidKey).remove(vendorKey).remove(migratedKey)
                    .remove("steering_cs35plus_key_138").remove("steering_protocol_migrated")
                    .remove(SteeringProtocol.PREFERENCE).commit();
        }
    }

    private void sendVendorBroadcast(String code, String state) throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        getTargetContext().sendOrderedBroadcast(new Intent(MainActivity.VENDOR_KEY_ACTION)
                        .setPackage(getTargetContext().getPackageName())
                        .putExtra("Key_code", code).putExtra("Key_state", state),
                null, new android.content.BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) { delivered.countDown(); }
                }, new android.os.Handler(android.os.Looper.getMainLooper()), 0, null, null);
        require(delivered.await(5, TimeUnit.SECONDS), "Vendor broadcast must be delivered");
        waitForIdleSync();
    }

    private void unbindObserver() {
        if (observerBound) {
            getTargetContext().unbindService(observer);
            observerBound = false;
        }
    }

    private boolean isServiceRunning(boolean foreground) {
        ActivityManager manager = (ActivityManager) getTargetContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(100)) {
            if (ConnectionService.class.getName().equals(info.service.getClassName())) {
                return info.foreground == foreground;
            }
        }
        return false;
    }

    private void awaitService(boolean exists, boolean foreground) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        boolean matches;
        do {
            matches = exists ? isServiceRunning(foreground)
                    : !isServiceRunning(true) && !isServiceRunning(false);
            if (matches) break;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        require(matches, "Expected service exists=" + exists + ", foreground=" + foreground);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
