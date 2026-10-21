package cn.autoplay.replacement;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class BoxOpenRequest {
    static final int COMMAND = 1;
    static final int DEFAULT_FRAME_RATE = VideoOptions.DEFAULT_FPS;
    static final int VIDEO_MODE = 5;
    static final int TRANSFER_SIZE = 16384;
    static final int DEFAULT_BOX_VERSION_CODE = 17;
    static final int CARPLAY_MODE = 2;
    static final int PAYLOAD_SIZE = 28;

    private BoxOpenRequest() {
    }

    static byte[] createPayload(int width, int height, int boxVersionCode) {
        return createPayload(width, height, DEFAULT_FRAME_RATE, boxVersionCode);
    }

    static byte[] createPayload(int width, int height, int frameRate, int boxVersionCode) {
        if (width <= 0 || width > 4000 || height <= 0 || height > 4000) {
            throw new IllegalArgumentException("Screen dimensions are outside protocol limits");
        }
        if (frameRate <= 0 || frameRate > 60) {
            throw new IllegalArgumentException("Frame rate must be between 1 and 60 FPS");
        }

        return ByteBuffer.allocate(PAYLOAD_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(width)
                .putInt(height)
                .putInt(frameRate)
                .putInt(VIDEO_MODE)
                .putInt(TRANSFER_SIZE)
                .putInt(boxVersionCode)
                .putInt(CARPLAY_MODE)
                .array();
    }

    static int versionCodeFallback(String boxSoftwareVersion) {
        if (boxSoftwareVersion == null || boxSoftwareVersion.isEmpty()) {
            return 0;
        }
        char suffix = Character.toUpperCase(boxSoftwareVersion.charAt(boxSoftwareVersion.length() - 1));
        switch (suffix) {
            case 'P':
                return 255;
            case 'I':
                return 0;
            case 'T':
                return 1;
            case 'Y':
                return 2;
            case 'B':
                return 3;
            case 'H':
                return 4;
            case 'D':
                return 13;
            case 'K':
                return 14;
            case 'J':
                return 15;
            case 'S':
                return 16;
            case 'G':
                return 17;
            case 'L':
                return 18;
            case 'M':
                return 19;
            default:
                return 0;
        }
    }
}
