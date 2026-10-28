package cn.autoplay.replacement;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;

public final class VehicleVoiceControl {
    static final String KEY_PACKAGE = "com.car.cs75_voice";
    static final String ASSISTANT_PACKAGE = "com.iflytek.cutefly.speechclient";

    private VehicleVoiceControl() { }

    static ApplicationInfo application(Context context, String packageName)
            throws PackageManager.NameNotFoundException {
        validatePackage(packageName);
        return context.getPackageManager().getApplicationInfo(packageName,
                PackageManager.GET_DISABLED_COMPONENTS);
    }

    static void change(Context context, String packageName, boolean disabled,
            RootUsbAuthorization.Callback callback) {
        validatePackage(packageName);
        int user = Process.myUid() / 100000;
        String command = "CLASSPATH=" + RootUsbAuthorization.quote(context.getApplicationInfo().sourceDir)
                + " /system/bin/app_process /system/bin " + VehicleVoiceControl.class.getName()
                + " " + packageName + " " + user + " " + disabled
                + " " + (SystemClock.elapsedRealtime() + 30000);
        RootUsbAuthorization.request(command, false, callback);
    }

    private static void validatePackage(String packageName) {
        if (!KEY_PACKAGE.equals(packageName) && !ASSISTANT_PACKAGE.equals(packageName)) {
            throw new IllegalArgumentException("Unsupported vehicle voice package");
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 4 || Process.myUid() != 0) {
                throw new IllegalArgumentException("Requires Root and four arguments");
            }
            String packageName = args[0];
            validatePackage(packageName);
            int user = Integer.parseInt(args[1]);
            if (user < 0 || (!"true".equals(args[2]) && !"false".equals(args[2]))) {
                throw new IllegalArgumentException("Invalid user or operation");
            }
            boolean disabled = Boolean.parseBoolean(args[2]);
            long deadline = Long.parseLong(args[3]);
            if (deadline <= SystemClock.elapsedRealtime()
                    || deadline - SystemClock.elapsedRealtime() > 30000) {
                throw new IllegalStateException("请求已过期，未修改应用状态");
            }
            RootUsbAuthorization.CommandResult result = run("pm",
                    disabled ? "disable-user" : "enable", "--user", String.valueOf(user), packageName);
            if (result.failure != null || !Integer.valueOf(0).equals(result.exitCode)) {
                throw new IllegalStateException(result.summary());
            }
            RootUsbAuthorization.CommandResult state = run("pm", "list", "packages",
                    disabled ? "-d" : "-e", "--user", String.valueOf(user), packageName);
            if (state.failure != null || !Integer.valueOf(0).equals(state.exitCode)
                    || !java.util.Arrays.asList(state.output.split("\\r?\\n"))
                            .contains("package:" + packageName)) {
                throw new IllegalStateException("命令已执行，但系统状态未确认，请检查后再操作。\n"
                        + result.summary() + "\n" + state.summary());
            }
            System.out.println((disabled ? "已确认禁用：" : "已确认恢复启用：") + packageName);
        } catch (Exception exception) {
            System.err.println("原车语音操作失败：" + exception.getMessage());
            System.exit(1);
        }
    }

    private static RootUsbAuthorization.CommandResult run(String... command)
            throws java.io.IOException, InterruptedException {
        return RootUsbAuthorization.collectResult(
                new ProcessBuilder(command).redirectErrorStream(true).start(), 5000);
    }
}
