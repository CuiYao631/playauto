package cn.autoplay.replacement;

import java.io.IOException;

public interface UsbTransport extends AutoCloseable {
    int bulkRead(byte[] buffer, int offset, int length, int timeoutMillis) throws IOException;
    int bulkWrite(byte[] buffer, int offset, int length, int timeoutMillis) throws IOException;
    String name();
    @Override void close();
}
