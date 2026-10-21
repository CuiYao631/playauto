package cn.manstep.phonemirrorBox.BoxInterface;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

public final class BoxCore {
    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        boolean loaded;
        String error = "";
        try {
            System.loadLibrary("nx1");
            loaded = true;
        } catch (Error | Exception exception) {
            loaded = false;
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
        AVAILABLE = loaded;
        LOAD_ERROR = error;
    }

    private BoxCore() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static String getLoadError() {
        return LOAD_ERROR;
    }

    public static native boolean stCheck(boolean encrypt, byte[] input, int nonce, byte[] output);

    public static native boolean stUsb(UsbDevice device, UsbManager manager);
}
