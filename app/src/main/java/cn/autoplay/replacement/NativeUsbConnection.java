package cn.autoplay.replacement;

import cn.manstep.phonemirrorBox.BoxInterface.BoxConnection;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class NativeUsbConnection implements UsbTransport {
    interface Driver {
        int init();
        int openDevice(int vendorId, int productId);
        int read(byte[] buffer, int length, int timeoutMillis);
        int write(byte[] buffer, int length, int timeoutMillis);
        String error(int code);
        void release();
    }

    // The original library owns process-global USB state. Release must wait for both directions.
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock(true);
    private static NativeUsbConnection active;
    private final Driver driver;
    private boolean closed;
    private volatile String failure;
    private static final int MAX_TRANSFER = 16384;

    private NativeUsbConnection(Driver driver) {
        this.driver = driver;
    }

    static NativeUsbConnection open(int vendorId, int productId) throws IOException {
        if (!BoxConnection.isAvailable()) {
            throw new IOException("libconnection 加载失败：" + BoxConnection.getLoadError());
        }
        BoxConnection nativeConnection = new BoxConnection();
        try {
            return open(new Driver() {
                public int init() { return nativeConnection.init(); }
                public int openDevice(int vid, int pid) { return nativeConnection.openDevice(vid, pid); }
                public int read(byte[] data, int length, int timeout) {
                    return nativeConnection.bulkTransferIn(data, length, timeout);
                }
                public int write(byte[] data, int length, int timeout) {
                    return nativeConnection.bulkTransferOut(data, length, timeout);
                }
                public String error(int code) { return BoxConnection.getError(code); }
                public void release() { nativeConnection.release(); }
            }, vendorId, productId);
        } catch (LinkageError | SecurityException exception) {
            throw new IOException("Native USB 接口不可用", exception);
        }
    }

    static NativeUsbConnection open(Driver driver, int vendorId, int productId) throws IOException {
        LOCK.writeLock().lock();
        try {
            if (active != null) throw new IOException("Native USB 已有连接，不能重复打开");
            boolean opened = false;
            try {
                int result = driver.init();
                if (result != 0) throw new IOException("Native USB 初始化失败：" + result
                        + " / " + driver.error(result));
                result = driver.openDevice(vendorId, productId);
                if (result != 0) throw new IOException("Native USB 打开失败：" + result + " / " + driver.error(result)
                        + "；请检查系统 USB 设备节点访问权限");
                NativeUsbConnection connection = new NativeUsbConnection(driver);
                active = connection;
                opened = true;
                return connection;
            } finally {
                if (!opened) driver.release();
            }
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    @Override public int bulkRead(byte[] buffer, int offset, int length, int timeoutMillis)
            throws IOException {
        return transfer(buffer, offset, length, timeoutMillis, true);
    }

    @Override public int bulkWrite(byte[] buffer, int offset, int length, int timeoutMillis)
            throws IOException {
        return transfer(buffer, offset, length, timeoutMillis, false);
    }

    private int transfer(byte[] buffer, int offset, int length, int timeoutMillis, boolean input)
            throws IOException {
        if (offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IndexOutOfBoundsException("Invalid USB buffer range");
        }
        if (timeoutMillis <= 0) throw new IllegalArgumentException("USB timeout must be positive");
        LOCK.readLock().lock();
        try {
            if (closed) throw new IOException("Native USB 已关闭");
            if (failure != null) throw new IOException(failure);
            if (length == 0) return 0;
            int chunk = Math.min(length, MAX_TRANSFER);
            byte[] data = new byte[chunk];
            if (!input) System.arraycopy(buffer, offset, data, 0, chunk);
            int count;
            try {
                count = input ? driver.read(data, chunk, Math.min(timeoutMillis, 1000))
                        : driver.write(data, chunk, Math.min(timeoutMillis, 1000));
            } catch (LinkageError | SecurityException exception) {
                failure = "Native USB 收发接口失败：" + exception.getClass().getSimpleName();
                throw new IOException(failure, exception);
            }
            // JNI loses libusb's partial-transfer length on error, including timeout.
            // Retrying could drop input bytes or duplicate output, so end the session.
            if (count < 0 || count > chunk) {
                failure = count < 0 ? "Native USB " + (input ? "读取" : "写入")
                        + "失败：" + count + " / " + driver.error(count)
                        : "Native USB 返回了无效的数据长度：" + count;
                throw new IOException(failure);
            }
            if (input && count > 0) System.arraycopy(data, 0, buffer, offset, count);
            return count;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    @Override public String name() { return "Native USB（实验）"; }

    @Override public void close() {
        LOCK.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            try {
                driver.release();
            } finally {
                active = null;
            }
        } finally {
            LOCK.writeLock().unlock();
        }
    }
}
