package cn.manstep.phonemirrorBox;

import java.io.IOException;

/** JNI names must match the original decoder library. */
public final class OpenH264Decoder implements AutoCloseable {
    private static final Object LOCK = new Object();
    private static OpenH264Decoder owner;
    private static final String LOAD_ERROR;
    private boolean closed;

    static {
        String error = null;
        try {
            System.loadLibrary("openH264decoder");
        } catch (UnsatisfiedLinkError failure) {
            error = failure.toString();
        }
        LOAD_ERROR = error;
    }

    public OpenH264Decoder() throws IOException {
        synchronized (LOCK) {
            if (LOAD_ERROR != null) throw new IOException("libopenH264decoder 加载失败：" + LOAD_ERROR);
            // The original JNI stores decoder state globally, not on the Java instance.
            if (owner != null) throw new IOException("原版 OpenH264 解码器正在使用中");
            nativeInit();
            owner = this;
        }
    }

    public int decode(byte[] input, byte[] output) throws IOException {
        synchronized (LOCK) {
            if (closed || owner != this) throw new IOException("OpenH264 解码器已关闭");
            if (input == null || input.length == 0 || output == null || output.length == 0) {
                throw new IllegalArgumentException("OpenH264 requires nonempty input and output buffers");
            }
            int result = decodeFrameOffset(input, 0, input.length, output, output.length);
            if (result < 0) throw new IOException("OpenH264 解码失败：" + result);
            return result;
        }
    }

    public int width() {
        synchronized (LOCK) {
            if (closed || owner != this) throw new IllegalStateException("OpenH264 decoder closed");
            return getWidth();
        }
    }

    public int height() {
        synchronized (LOCK) {
            if (closed || owner != this) throw new IllegalStateException("OpenH264 decoder closed");
            return getHeight();
        }
    }

    @Override public void close() {
        synchronized (LOCK) {
            if (closed) return;
            closed = true;
            if (owner == this) {
                try {
                    nativeDestroy();
                } finally {
                    owner = null;
                }
            }
        }
    }

    private native void nativeInit();
    private native void nativeDestroy();
    private native int decodeFrameOffset(byte[] input, int offset, int length, byte[] output, int capacity);
    private native int getWidth();
    private native int getHeight();
}
