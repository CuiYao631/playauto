package cn.autoplay.replacement;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class RootUsbAuthorization {
    interface Callback {
        void onComplete(String result);
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final int TIMEOUT_MS = 30000;
    private static String rootStatus = "未授权";

    static String rootStatus() {
        return rootStatus;
    }

    private RootUsbAuthorization() { }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static String command(String apkPath, int uid, UsbDevice device) {
        return "CLASSPATH=" + quote(apkPath) + " /system/bin/app_process /system/bin "
                + RootUsbGrant.class.getName() + " " + uid + " " + quote(device.getDeviceName())
                + " " + device.getVendorId() + " " + device.getProductId()
                + " " + (android.os.SystemClock.elapsedRealtime() + TIMEOUT_MS);
    }

    static void request(Context context, UsbDevice device, Callback callback) {
        request(command(context.getApplicationInfo().sourceDir, context.getApplicationInfo().uid, device),
                false, callback);
    }

    static void requestRoot(Callback callback) {
        request("id", true, callback);
    }

    static void request(String command, boolean checkRoot, Callback callback) {
        request(command, checkRoot, TIMEOUT_MS, callback);
    }

    static void request(String command, boolean checkRoot, int timeoutMillis, Callback callback) {
        if (!RUNNING.compareAndSet(false, true)) {
            callback.onComplete("已有 Root 请求正在进行，请等待完成");
            Log.w("AutoPlayRootUsb", "Duplicate root request rejected");
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String result;
            boolean rootGranted = false;
            try {
                CommandResult execution = execute(command, timeoutMillis);
                rootGranted = execution.isRoot();
                result = checkRoot ? execution.rootSummary() : execution.summary();
            } catch (IOException exception) {
                Log.e("AutoPlayRootUsb", "Cannot execute root USB helper", exception);
                result = "无法启动 su 或读取结果：" + exception.getMessage();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                Log.e("AutoPlayRootUsb", "Root USB helper interrupted", exception);
                result = "Root 请求已中断";
            } catch (SecurityException exception) {
                Log.e("AutoPlayRootUsb", "Root execution denied", exception);
                result = "系统禁止执行 su：" + exception.getMessage();
            } finally {
                RUNNING.set(false);
            }
            String completed = result;
            boolean granted = rootGranted;
            main.post(() -> {
                if (checkRoot) rootStatus = granted ? "已授权" : "未授权";
                callback.onComplete(completed);
            });
        }, "playauto-root-usb").start();
    }

    private static CommandResult execute(String command, int timeoutMillis) throws IOException, InterruptedException {
        return collectResult(new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start(), timeoutMillis);
    }

    static String collect(Process process, int timeoutMillis) throws IOException, InterruptedException {
        return collectResult(process, timeoutMillis).summary();
    }

    static final class CommandResult {
        final Integer exitCode;
        final String output;
        final String failure;

        CommandResult(Integer exitCode, String output, String failure) {
            this.exitCode = exitCode;
            this.output = output;
            this.failure = failure;
        }

        boolean isRoot() {
            return failure == null && Integer.valueOf(0).equals(exitCode)
                    && java.util.regex.Pattern.compile("(?m)^uid=0(?:\\(|\\s|$)").matcher(output).find();
        }

        String summary() {
            return failure != null ? failure : "Root 进程退出码：" + exitCode
                    + (output.isEmpty() ? "；无输出" : "\n" + output);
        }

        String rootSummary() {
            return (isRoot() ? "已确认 Root 权限：su 返回 UID 0"
                    : "未确认 Root 权限") + "\n" + summary();
        }
    }

    static CommandResult collectResult(Process process, int timeoutMillis) throws IOException, InterruptedException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicReference<IOException> readFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[512];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (output) {
                        int available = 4096 - output.size();
                        if (available > 0) output.write(buffer, 0, Math.min(count, available));
                    }
                }
            } catch (IOException exception) {
                readFailure.set(exception);
            }
        }, "playauto-root-usb-output");
        reader.setDaemon(true);
        reader.start();
        try {
            // Process.waitFor(timeout, unit) is unavailable on Android 4.4.
            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis;
            Integer exitCode = null;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    exitCode = process.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(100);
                }
            }
            if (exitCode == null) return new CommandResult(null, "",
                    "Root 请求超时，未确认授权；请检查 root 管理器");
            reader.join(TimeUnit.SECONDS.toMillis(1));
            String text;
            synchronized (output) {
                text = output.toString("UTF-8").replace('\u0000', ' ').trim();
            }
            if (readFailure.get() != null) throw readFailure.get();
            if (reader.isAlive()) return new CommandResult(exitCode, text,
                    "Root 进程已退出，但输出未结束；未确认授权");
            return new CommandResult(exitCode, text, null);
        } finally {
            process.destroy();
            process.getInputStream().close();
            process.getOutputStream().close();
            process.getErrorStream().close();
        }
    }
}
