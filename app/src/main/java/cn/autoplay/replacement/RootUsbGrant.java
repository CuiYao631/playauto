package cn.autoplay.replacement;

import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.InvocationTargetException;

/** Entry point loaded by app_process only after an explicit su approval. */
public final class RootUsbGrant {
    private RootUsbGrant() { }

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0) throw new SecurityException("su did not provide UID 0");
            if (args.length != 5) throw new IllegalArgumentException("Expected UID, device name, VID, PID and deadline");
            int uid = Integer.parseInt(args[0]);
            int vid = Integer.parseInt(args[2]);
            int pid = Integer.parseInt(args[3]);
            long deadline = Long.parseLong(args[4]);
            checkDeadline(deadline);
            if (uid < 10000 || vid != 0x1314 || (pid != 0x1520 && pid != 0x1521)) {
                throw new SecurityException("Only the PlayAuto UID and supported USB box are allowed");
            }
            Object packages = service("package", "android.content.pm.IPackageManager");
            Class<?> packageType = Class.forName("android.content.pm.IPackageManager");
            int user = uid / 100000;
            Object actualUid;
            if (Build.VERSION.SDK_INT >= 33) {
                actualUid = packageType.getMethod("getPackageUid", String.class, long.class, int.class)
                        .invoke(packages, "cn.autoplay.replacement", 0L, user);
            } else if (Build.VERSION.SDK_INT >= 24) {
                actualUid = packageType.getMethod("getPackageUid", String.class, int.class, int.class)
                        .invoke(packages, "cn.autoplay.replacement", 0, user);
            } else {
                actualUid = packageType.getMethod("getPackageUid", String.class, int.class)
                        .invoke(packages, "cn.autoplay.replacement", user);
            }
            if (!Integer.valueOf(uid).equals(actualUid)) {
                throw new SecurityException("Requested UID does not belong to PlayAuto");
            }
            Object usb = service("usb", "android.hardware.usb.IUsbManager");
            Class<?> usbType = Class.forName("android.hardware.usb.IUsbManager");
            Bundle devices = new Bundle();
            usbType.getMethod("getDeviceList", Bundle.class).invoke(usb, devices);
            Object candidate = devices.get(args[1]);
            if (!(candidate instanceof UsbDevice)) throw new IllegalArgumentException("USB box is no longer attached");
            UsbDevice device = (UsbDevice) candidate;
            if (!args[1].equals(device.getDeviceName()) || device.getVendorId() != vid
                    || device.getProductId() != pid) {
                throw new SecurityException("Attached USB identity changed");
            }
            checkDeadline(deadline);
            usbType.getMethod("grantDevicePermission", UsbDevice.class, int.class)
                    .invoke(usb, device, uid);
            System.out.println("PLAYAUTO_USB_GRANT_REQUESTED");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            Throwable cause = exception instanceof InvocationTargetException
                    && exception.getCause() != null ? exception.getCause() : exception;
            System.err.println("Root USB grant failed: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            System.exit(1);
        }
    }

    private static void checkDeadline(long deadline) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (deadline <= now || deadline - now > 30000) {
            throw new SecurityException("Root USB authorization request has expired");
        }
    }

    private static Object service(String name, String interfaceName) throws ReflectiveOperationException {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        if (!(binder instanceof IBinder)) throw new IllegalStateException("Missing system service: " + name);
        return Class.forName(interfaceName + "$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }
}
