package cn.manstep.phonemirrorBox.BoxInterface;

public final class BoxConnection {
    private static final String LOAD_ERROR;

    static {
        String error = "";
        try {
            System.loadLibrary("connection");
        } catch (LinkageError | SecurityException exception) {
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
        LOAD_ERROR = error;
    }

    public static boolean isAvailable() { return LOAD_ERROR.isEmpty(); }
    public static String getLoadError() { return LOAD_ERROR; }

    // JNI registers these exact class and method names. Initialization is explicit.
    public native int init();
    public native int openDevice(int vendorId, int productId);
    public native int getDeviceList(String[] devices);
    public native int bulkTransferIn(byte[] buffer, int length, int timeoutMillis);
    public native int bulkTransferOut(byte[] buffer, int length, int timeoutMillis);
    public native void release();
    public static native String getError(int code);
}
