package cn.autoplay.replacement;

import java.util.ArrayList;
import java.util.List;

final class VideoOptions {
    static final int HARDWARE = 1;
    static final int SOFTWARE = 3;
    static final int DEFAULT_FPS = 30;
    static final int[] FRAME_RATES = {60, 55, 50, 45, 40, 35, 30, 25, 20};
    /** 设置页可选帧率，只保留 30 与 60。 */
    static final int[] SELECTABLE_FRAME_RATES = {60, 30};

    private VideoOptions() { }

    /**
     * 把任意历史帧率归一到设置页可选的 30 / 60。
     *
     * <p>旧版本允许 20~60 的九档取值，升级后这些值仍然存在于偏好里，
     * 直接用于 {@link #SELECTABLE_FRAME_RATES} 的下标查找会抛异常，因此统一收敛。
     */
    static int normalizeFrameRate(int value) {
        if (value == 60 || value == 30) return value;
        for (int candidate : FRAME_RATES) {
            if (candidate == value) return value >= 45 ? 60 : 30;
        }
        return DEFAULT_FPS;
    }

    static int effectiveFrameRate(int decoder, int selected) {
        if (decoder == SOFTWARE) return 25;
        for (int value : FRAME_RATES) if (value == selected) return selected;
        throw new IllegalArgumentException("Unsupported video frame rate: " + selected);
    }

    static int migrateDecoder(int previous) {
        if (previous == 0 || previous == HARDWARE) return HARDWARE;
        if (previous == 2 || previous == SOFTWARE) return SOFTWARE;
        throw new IllegalArgumentException("Invalid decoder mode: " + previous);
    }

    static int migrateFrameRate(int previous) {
        return previous == 0 ? 30 : previous == 1 ? 60 : effectiveFrameRate(HARDWARE, previous);
    }

    static int defaultFrameRate(String device, String productDevice) {
        return "Freescale-SABRESD-MX6DQ-4.4.2(19)".equals(device)
                || "HUAWEI-ICHU-X1EV-ICHU-X1EV-10(29)".equals(productDevice) ? 60 : DEFAULT_FPS;
    }

    static int automaticDpi(int width, int height) {
        validateSize(width, height);
        int maxW = width > height ? 1280 : 720;
        int maxH = width > height ? 720 : 1280;
        int w = ((int) (maxH * 1.0f * width / height)) & 65534;
        int h = maxH;
        if (w > maxW) {
            w = maxW;
            h = ((int) (maxW * 1.0f * height / width)) & 65534;
        }
        int area = w * h;
        int dpi = 100;
        if (area >= 384000) {
            dpi = (int) ((((area - 384000) * 23 / 998400) + 120) * 1.25f);
            dpi -= 10 - dpi % 10;
        }
        return width > height ? (int) (dpi * 1.2f) : dpi;
    }

    static List<String> resolutions(int width, int height) {
        validateSize(width, height);
        List<String> smaller = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double scale = 1.0 - i * 0.05;
            int w = ((int) Math.ceil(width * scale)) & ~1;
            int h = ((int) Math.ceil(height * scale)) & ~1;
            if (width > height ? w < 730 || h < 480 : w < 570 || h < 800) break;
            smaller.add(w + "x" + h);
        }
        List<String> result = new ArrayList<>();
        if (smaller.isEmpty()) result.add(width + "x" + height);
        else if (smaller.size() <= 3) result.addAll(smaller);
        else {
            result.add(smaller.get(0));
            result.add(smaller.get(smaller.size() / 2));
            result.add(smaller.get(smaller.size() - 1));
        }
        if (width == 1920 && height >= 1080 && height < 1152) result.add("1920x1152");
        else if (width == 1280 && height >= 720 && height < 768) result.add("1088x768");
        for (int i = 1; i < 4; i++) {
            double scale = 1.0 + i * 0.05;
            int w = (((int) Math.ceil(width * scale)) + 1) & ~1;
            int h = (((int) Math.ceil(height * scale)) + 1) & ~1;
            if (w <= 4000 && h <= 4000) result.add(w + "x" + h);
        }
        return result;
    }

    static int[] parseSize(String value) {
        if (value == null || !value.matches("[0-9]{1,4}x[0-9]{1,4}")) {
            throw new IllegalArgumentException("Invalid video size: " + value);
        }
        String[] parts = value.split("x");
        int w = Integer.parseInt(parts[0]);
        int h = Integer.parseInt(parts[1]);
        validateSize(w, h);
        return new int[]{w, h};
    }

    private static void validateSize(int width, int height) {
        if (width < 2 || height < 2 || width > 4000 || height > 4000
                || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("Invalid even video size: " + width + "x" + height);
        }
    }
}
