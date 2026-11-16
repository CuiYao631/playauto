package cn.autoplay.replacement;

import java.util.HashMap;
import java.util.Map;

final class VendorKeys {
    private final Map<String, Long> pressed = new HashMap<>();
    private long lastMain = -1000;

    static boolean supported(String code) {
        return "NEXT".equals(code) || "PRE".equals(code) || "VR".equals(code) || "MAIN".equals(code);
    }

    /**
     * 是否支持区分单击/长按。
     *
     * <p>只有语音键做长按：DOWN/UP 成对到达，可测按住时长。MAIN 是触摸按钮，
     * 只有按下事件，无法计时，因此不支持长按。
     */
    static boolean supportsLongPress(String code) {
        return "VR".equals(code);
    }

    static boolean stateSupported(String state) {
        return "DOWN".equals(state) || "UP".equals(state);
    }

    boolean press(String code, String state, long now) {
        if (!supported(code) || !stateSupported(state)) return false;
        if ("UP".equals(state)) {
            pressed.remove(code);
            return false;
        }
        if ("MAIN".equals(code)) {
            if (now - lastMain < 350) return false;
            lastMain = now;
            return true;
        }
        Long previous = pressed.put(code, now);
        return previous == null || now - previous > 5000;
    }

    void reset() {
        pressed.clear();
        lastMain = -1000;
    }
}
