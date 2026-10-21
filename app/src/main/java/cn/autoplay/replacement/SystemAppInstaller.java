package cn.autoplay.replacement;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.security.MessageDigest;

/** Root-only helper for an explicitly confirmed legacy system installation. */
public final class SystemAppInstaller {
    static final String TARGET = "/system/app/PlayAutoReplacement.apk";
    private static final String STAGE = "/system/app/.PlayAutoReplacement.installing";
    private static final String MARKER = "/system/.playauto-replacement-install";
    private static final String PACKAGE = "cn.autoplay.replacement";
    private static final String[] LIBRARIES = {"libnx1.so", "libconnection.so", "libopenH264decoder.so"};

    private SystemAppInstaller() { }

    static boolean supported(int api) { return api == 19 || api == 20; }

    static String command(ApplicationInfo info, String action) {
        if (!"check".equals(action) && !"install".equals(action) && !"undo".equals(action)) {
            throw new IllegalArgumentException("Unsupported system-app operation");
        }
        return "CLASSPATH=" + RootUsbAuthorization.quote(info.sourceDir)
                + " /system/bin/app_process /system/bin " + SystemAppInstaller.class.getName()
                + " " + action + " " + info.uid + " "
                + (android.os.SystemClock.elapsedRealtime() + 30000);
    }

    public static void main(String[] args) {
        try {
            if (!supported(Build.VERSION.SDK_INT)) throw new IOException("仅支持 Android 4.4（API 19–20）");
            if (Process.myUid() != 0) throw new SecurityException("需要真实 UID 0");
            if (args.length != 3 || Long.parseLong(args[2]) <= android.os.SystemClock.elapsedRealtime()) {
                throw new IOException("请求参数无效或已过期");
            }
            int uid = Integer.parseInt(args[1]);
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "package");
            Object pm = Class.forName("android.content.pm.IPackageManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            ApplicationInfo app = (ApplicationInfo) Class.forName("android.content.pm.IPackageManager")
                    .getMethod("getApplicationInfo", String.class, int.class, int.class)
                    .invoke(pm, PACKAGE, 0, uid / 100000);
            if (app == null || app.uid != uid) throw new SecurityException("安装包 UID 不匹配");
            File backup = new File(app.dataDir, "files/system-app-original.apk");
            File source = new File(app.sourceDir);
            File target = new File(TARGET);
            File marker = new File(MARKER);
            String[] mount = systemMount();
            if ("check".equals(args[0])) {
                LibraryInstall[] libraries = planLibraries(new File(app.nativeLibraryDir), new File("/system/lib"));
                StringBuilder libraryDetails = new StringBuilder();
                for (LibraryInstall library : libraries) {
                    libraryDetails.append("\n").append(library.target).append("：")
                            .append(library.create ? "将复制（当前不存在）" : "内容一致，将复用");
                }
                System.out.println("Android API " + Build.VERSION.SDK_INT
                        + "\n系统应用：" + ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "是" : "否")
                        + "\n源 APK：" + source + "\n目标：" + TARGET
                        + "\n/system 挂载：" + mount[0] + " " + mount[1]
                        + "\n目标已存在：" + target.exists()
                        + "\n备份：" + backup + "\n恢复记录：" + marker.exists()
                        + "\nSELinux enforcing：" + enforcing()
                        + libraryDetails
                        + "\n检测未修改系统；写入能力只有实际操作时才能确认。"
                        + "\nPLAYAUTO_SYSTEM_CHECK_OK");
                return;
            }
            if (!"install".equals(args[0]) && !"undo".equals(args[0])) {
                throw new IOException("未知操作");
            }
            boolean install = "install".equals(args[0]);
            LibraryInstall[] libraries = null;
            java.util.List<File> undoLibraries = new java.util.ArrayList<>();
            if (enforcing()) throw new IOException("SELinux enforcing：拒绝实验转换，不修改策略");
            if (install) {
                if ((app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                    throw new IOException("已经是系统应用，不重复转换");
                }
                if (target.exists() || marker.exists() || new File(STAGE).exists() || backup.exists()) {
                    throw new IOException("目标、备份或恢复记录已存在；拒绝覆盖，请先检查");
                }
                if (!source.isFile() || !source.getCanonicalPath().startsWith("/data/app/")) {
                    throw new IOException("源安装位置不受支持");
                }
                libraries = planLibraries(new File(app.nativeLibraryDir), new File("/system/lib"));
                long required = source.length() + 8 * 1024 * 1024;
                for (LibraryInstall library : libraries) if (library.create) required += library.source.length();
                if (new android.os.StatFs("/system").getAvailableBytes() < required) {
                    throw new IOException("/system 空间不足（需额外保留 8 MiB）");
                }
                copyNew(source, backup);
                permissions(backup);
            } else {
                if (!marker.isFile() || !target.isFile() || !backup.isFile()) {
                    throw new IOException("缺少本功能的 APK、备份或恢复记录，拒绝删除");
                }
                String expected;
                try (BufferedReader reader = new BufferedReader(new FileReader(marker))) {
                    expected = reader.readLine();
                    String line;
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    while ((line = reader.readLine()) != null) {
                        String[] fields = line.split(" ");
                        if (fields.length != 3 || !java.util.Arrays.asList(LIBRARIES).contains(fields[0])
                                || !seen.add(fields[0]) || !fields[1].matches("[0-9a-f]{64}")
                                || (!"created".equals(fields[2]) && !"reused".equals(fields[2]))) {
                            throw new IOException("库恢复记录无效，拒绝撤销");
                        }
                        File library = new File("/system/lib", fields[0]);
                        requireRegularPath(library);
                        if (!library.isFile() || !digest(library).equals(fields[1])) {
                            throw new IOException("系统库已变更或丢失，拒绝撤销：" + library);
                        }
                        if ("created".equals(fields[2])) {
                            File userLibrary = new File(app.nativeLibraryDir, fields[0]);
                            if (userLibrary.getCanonicalPath().equals(library.getCanonicalPath())
                                    || !userLibrary.isFile() || !digest(userLibrary).equals(fields[1])) {
                                throw new IOException("用户安装没有独立且一致的库副本，拒绝撤销：" + fields[0]);
                            }
                            undoLibraries.add(library);
                        }
                    }
                }
                if (!digest(target).equals(expected) || !digest(backup).equals(expected)) {
                    throw new IOException("系统 APK 或备份已变更，拒绝撤销");
                }
                if (source.getCanonicalPath().equals(target.getCanonicalPath())) {
                    throw new IOException("当前正从系统 APK 运行。请先用备份覆盖安装为用户更新，再撤销");
                }
                if (!source.getCanonicalPath().startsWith("/data/app/") || !digest(source).equals(expected)) {
                    throw new IOException("请先将原始备份作为用户更新安装，再撤销");
                }
            }
            boolean readOnly = hasOption(mount[1], "ro");
            try {
                if (readOnly) remount(mount[0], "rw");
                if (install) {
                    File stage = new File(STAGE);
                    copyNew(backup, stage);
                    permissions(stage);
                    if (!marker.createNewFile()) throw new IOException("恢复记录已存在，拒绝覆盖");
                    try (FileOutputStream output = new FileOutputStream(marker)) {
                        output.write((digest(backup) + "\n").getBytes("UTF-8"));
                        for (LibraryInstall library : libraries) {
                            output.write((library.target.getName() + " " + library.hash + " "
                                    + (library.create ? "created" : "reused") + "\n").getBytes("UTF-8"));
                        }
                        output.getFD().sync();
                    }
                    permissions(marker);
                    for (LibraryInstall library : libraries) {
                        if (library.create) {
                            copyNew(library.source, library.stage);
                            permissions(library.stage);
                            if (!digest(library.stage).equals(library.hash)) {
                                throw new IOException("库源文件已变化，拒绝发布：" + library.source);
                            }
                            if (library.target.exists() || !library.stage.renameTo(library.target)) {
                                throw new IOException("无法发布系统库（目标可能已存在）：" + library.target);
                            }
                        }
                        requireRegularPath(library.target);
                        if (!digest(library.target).equals(library.hash)) {
                            throw new IOException("发布前系统库校验失败：" + library.target);
                        }
                        System.out.println((library.create ? "已复制并校验：" : "已复用：") + library.target);
                    }
                    if (!stage.renameTo(target)) throw new IOException("无法发布系统 APK；请检查暂存文件和恢复记录");
                    System.out.println("系统 APK 已写入并校验。原安装及备份保留；请手动重启，再检查系统应用身份。"
                            + "\n备份：" + backup + "\n不会自动重启，也不保证获得 MANAGE_USB。");
                } else {
                    if (!target.delete()) throw new IOException("无法删除已校验的系统 APK");
                    for (File library : undoLibraries) {
                        if (!library.delete()) throw new IOException("APK 已撤销，但无法删除本功能新建的库：" + library);
                    }
                    if (!marker.delete()) throw new IOException("APK 已撤销，但恢复记录未能删除：" + MARKER);
                    System.out.println("已撤销本功能创建的系统 APK 和新建库；复用的系统库、用户安装和备份保留，请手动重启。");
                }
            } finally {
                if (readOnly) remount(mount[0], "ro");
            }
        } catch (Exception | LinkageError exception) {
            System.err.println("系统应用操作失败：" + exception.getClass().getSimpleName()
                    + "：" + exception.getMessage());
            System.exit(1);
        }
    }

    private static boolean enforcing() throws IOException {
        File file = new File("/sys/fs/selinux/enforce");
        if (!file.exists()) return false;
        try (FileInputStream input = new FileInputStream(file)) { return input.read() != '0'; }
    }

    static final class LibraryInstall {
        final File source;
        final File target;
        final File stage;
        final String hash;
        final boolean create;

        LibraryInstall(File source, File target) throws Exception {
            this.source = source;
            this.target = target;
            stage = new File(target.getParentFile(), ".PlayAutoReplacement." + target.getName() + ".installing");
            requireRegularPath(source);
            requireRegularPath(target);
            requireRegularPath(stage);
            if (!source.isFile()) throw new IOException("缺少当前应用的 native 库：" + source);
            if (stage.exists()) throw new IOException("库暂存文件已存在，拒绝覆盖：" + stage);
            hash = digest(source);
            create = !target.exists();
            if (!create && (!target.isFile() || !hash.equals(digest(target)))) {
                throw new IOException("系统库内容不同，拒绝覆盖：" + target);
            }
        }
    }

    static LibraryInstall[] planLibraries(File sourceDir, File systemDir) throws Exception {
        LibraryInstall[] result = new LibraryInstall[LIBRARIES.length];
        for (int i = 0; i < LIBRARIES.length; i++) {
            result[i] = new LibraryInstall(new File(sourceDir, LIBRARIES[i]), new File(systemDir, LIBRARIES[i]));
        }
        return result;
    }

    private static void requireRegularPath(File file) throws IOException {
        if (!file.getAbsolutePath().equals(file.getCanonicalPath())) {
            throw new IOException("拒绝符号链接或非规范路径：" + file);
        }
    }

    private static boolean hasOption(String options, String option) {
        for (String value : options.split(",")) if (value.equals(option)) return true;
        return false;
    }

    private static String[] systemMount() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(" ");
                if (fields.length >= 4 && "/system".equals(fields[1])) {
                    if (!fields[0].startsWith("/dev/") || !"ext4".equals(fields[2])) {
                        throw new IOException("仅支持独立 ext4 /system 分区");
                    }
                    return new String[]{fields[0], fields[3]};
                }
            }
        }
        throw new IOException("未找到独立 /system 挂载点");
    }

    private static void remount(String device, String mode) throws Exception {
        java.lang.Process process = new ProcessBuilder("/system/bin/mount", "-o", "remount," + mode,
                device, "/system").redirectErrorStream(true).start();
        String result = RootUsbAuthorization.collect(process, 5000);
        String[] current = systemMount();
        if (!hasOption(current[1], mode)) throw new IOException("重新挂载 " + mode + " 失败：" + result);
    }

    private static void permissions(File file) throws Exception {
        Object result = Class.forName("android.os.FileUtils").getMethod("setPermissions",
                String.class, int.class, int.class, int.class).invoke(null, file.getPath(), 0644, 0, 0);
        if (!Integer.valueOf(0).equals(result)) throw new IOException("无法设置 root:root / 0644：" + file);
    }

    static void copyNew(File source, File target) throws Exception {
        if (!target.createNewFile()) throw new IOException("拒绝覆盖：" + target);
        try (FileInputStream input = new FileInputStream(source);
                FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (!digest(source).equals(digest(target))) throw new IOException("APK 复制校验失败：" + target);
    }

    static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return result.toString();
    }
}
