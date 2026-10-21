package cn.autoplay.replacement;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class TouchFrame {
    static final int POINT_SIZE = 16;
    static final int MAX_POINTERS = 5;

    private TouchFrame() {
    }

    public static byte[] encode(float[] normalizedX, float[] normalizedY, int[] pointerIds, int actionState) {
        if (normalizedX == null || normalizedY == null || pointerIds == null
                || normalizedX.length != normalizedY.length
                || normalizedX.length != pointerIds.length
                || normalizedX.length == 0 || normalizedX.length > MAX_POINTERS
                || actionState < 0 || actionState > 2) {
            throw new IllegalArgumentException("Invalid touch pointer arrays");
        }

        ByteBuffer payload = ByteBuffer.allocate(normalizedX.length * POINT_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < normalizedX.length; index++) {
            if (pointerIds[index] < 0 || pointerIds[index] >= MAX_POINTERS) {
                throw new IllegalArgumentException("Pointer ID is outside the box protocol range");
            }
            payload.putFloat(clamp(normalizedX[index]));
            payload.putFloat(clamp(normalizedY[index]));
            payload.putInt(actionState);
            payload.putInt(pointerIds[index]);
        }
        return payload.array();
    }

    private static float clamp(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(value, 1.0f));
    }
}
