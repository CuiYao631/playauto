package cn.autoplay.replacement;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class VideoFrame {
    static final int METADATA_SIZE = 20;
    private static final int MAX_DIMENSION = 4000;

    private final int width;
    private final int height;
    private final int flags;
    private final long presentationTimeUs;
    private final byte[] h264Data;

    private VideoFrame(int width, int height, int flags, long presentationTimeUs, byte[] h264Data) {
        this.width = width;
        this.height = height;
        this.flags = flags;
        this.presentationTimeUs = presentationTimeUs;
        this.h264Data = h264Data;
    }

    public static VideoFrame parse(byte[] payload, boolean hasPresentationTimestamp) throws IOException {
        if (payload == null || payload.length <= METADATA_SIZE) {
            throw new IOException("Incomplete CMD_VIDEO_FRAME payload");
        }

        ByteBuffer metadata = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int width = metadata.getInt(0);
        int height = metadata.getInt(4);
        int flags = metadata.getInt(8);
        long presentationTimeUs = hasPresentationTimestamp ? metadata.getLong(12) : 0L;
        if (width <= 0 || width > MAX_DIMENSION || height <= 0 || height > MAX_DIMENSION) {
            throw new IOException("Invalid video dimensions: " + width + "x" + height);
        }

        byte[] h264Data = Arrays.copyOfRange(payload, METADATA_SIZE, payload.length);
        return new VideoFrame(width, height, flags, presentationTimeUs, h264Data);
    }

    /**
     * 解析副屏帧 {@code CMD_DASHBOARD_FRAME}(44)。
     *
     * <p>头长度与主屏帧一致（20 字节），区别在时间戳：副屏只有 4 字节 int
     * （毫秒，偏移 12），偏移 16 的 4 字节保留。原版直接把它当
     * {@code MediaCodec.queueInputBuffer} 的 pts 使用。
     */
    public static VideoFrame parseDashboard(byte[] payload) throws IOException {
        if (payload == null || payload.length <= METADATA_SIZE) {
            throw new IOException("Incomplete CMD_DASHBOARD_FRAME payload");
        }

        ByteBuffer metadata = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int width = metadata.getInt(0);
        int height = metadata.getInt(4);
        int flags = metadata.getInt(8);
        int timestampMs = metadata.getInt(12);
        if (width <= 0 || width > MAX_DIMENSION || height <= 0 || height > MAX_DIMENSION) {
            throw new IOException("Invalid dashboard dimensions: " + width + "x" + height);
        }

        long presentationTimeUs = timestampMs > 0 ? timestampMs * 1000L : 0L;
        byte[] h264Data = Arrays.copyOfRange(payload, METADATA_SIZE, payload.length);
        return new VideoFrame(width, height, flags, presentationTimeUs, h264Data);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFlags() {
        return flags;
    }

    public long getPresentationTimeUs() {
        return presentationTimeUs;
    }

    public byte[] getH264Data() {
        return h264Data;
    }
}
