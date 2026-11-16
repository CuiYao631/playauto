package cn.autoplay.replacement;

import android.content.SharedPreferences;

final class SteeringProtocol {
    static final String PREFERENCE = "steering_protocol";
    static final String[] LABELS = {"默认", "长安 CS35Plus"};
    private static final String MIGRATED = "steering_protocol_migrated";

    private SteeringProtocol() { }

    static void migrate(SharedPreferences prefs) {
        if (prefs.getBoolean(MIGRATED, false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("steering_vendor_") && entry.getValue() instanceof Integer) {
                String target = "steering_cs35plus_vendor_" + key.substring("steering_vendor_".length());
                if (!prefs.contains(target)) editor.putInt(target, (Integer) entry.getValue());
                editor.remove(key);
            }
        }
        editor.putBoolean(MIGRATED, true).apply();
    }

    static boolean vendorEnabled(SharedPreferences prefs) {
        return prefs.getInt(PREFERENCE, 0) == 1;
    }

    static int playbackAction(SharedPreferences prefs, int action) {
        if (!vendorEnabled(prefs)) return action;
        return action == 10001 ? 10004 : action == 10002 ? 10005 : action;
    }

    /** 长按绑定的键名前缀，完整键形如 {@code steering_key_long_25}。 */
    static final String LONG_SUFFIX = "long_";
    /** 区分单击与长按的按住时长阈值。 */
    static final long LONG_PRESS_MS = 800L;

    /**
     * 松手时决定执行哪个动作。
     *
     * @param singleCommand 单击槽位绑定的功能，未绑定为 -1
     * @param longCommand   长按槽位绑定的功能，未绑定为 -1
     * @param heldMs        实际按住时长
     * @return 要执行的功能；不应执行时返回 -1
     */
    static int resolveAction(int singleCommand, int longCommand, long heldMs) {
        if (longCommand >= 0 && heldMs >= LONG_PRESS_MS) {
            return longCommand;
        }
        // 短按只走单击槽位；只绑了长按的键在短按时保持静默，避免两种语义混淆。
        return singleCommand;
    }

    /**
     * 按键按下/松开的状态机，用于「按住满 {@link #LONG_PRESS_MS} 立即触发长按、无需松手」。
     *
     * <p>键用字符串标识，同时服务 Android 键码（`"25"`）与厂家广播码（`"VR"`）。
     *
     * <p>纯逻辑、不依赖 Android，便于离线自测：
     * <ol>
     *   <li>{@link #begin} 记录按下时刻与两个槽位的动作</li>
     *   <li>{@link #longActionIfDue} 由计时器驱动，到点返回长按动作并标记已触发</li>
     *   <li>{@link #finish} 由松手触发；若长按已触发则返回 -1（不补发单击）</li>
     * </ol>
     */
    static final class PressState {
        private String key;
        private int singleCommand = -1;
        private int longCommand = -1;
        private long downTime;
        private boolean longFired;

        /** 开始一次按下。只有在绑定了长按槽位时才需要进入本状态机。 */
        void begin(String key, int singleCommand, int longCommand, long now) {
            this.key = key;
            this.singleCommand = singleCommand;
            this.longCommand = longCommand;
            this.downTime = now;
            this.longFired = false;
        }

        boolean isPressed() {
            return key != null;
        }

        boolean isPressed(String key) {
            return this.key != null && this.key.equals(key);
        }

        String pressedKey() {
            return key;
        }

        boolean isLongFired() {
            return longFired;
        }

        long heldMs(long now) {
            return key == null ? 0 : now - downTime;
        }

        /**
         * 计时器到期时调用。
         *
         * @return 需要执行的长按动作；未到时间、已触发或未绑定长按时返回 -1
         */
        int longActionIfDue(long now) {
            if (!isPressed() || longFired) return -1;
            int action = resolveAction(singleCommand, longCommand, heldMs(now));
            if (action < 0 || action != longCommand) return -1;
            longFired = true;
            return action;
        }

        /**
         * 松手时调用，并重置状态。
         *
         * @return 需要执行的单击动作；长按已触发或未绑定时返回 -1
         */
        int finish(long now) {
            int action = longFired ? -1 : resolveAction(singleCommand, longCommand, heldMs(now));
            reset();
            return action;
        }

        void reset() {
            key = null;
            singleCommand = -1;
            longCommand = -1;
            downTime = 0;
            longFired = false;
        }
    }

    static String androidPrefix(SharedPreferences prefs) {
        return vendorEnabled(prefs) ? "steering_cs35plus_key_" : "steering_key_";
    }

    static String vendorPrefix() {
        return "steering_cs35plus_vendor_";
    }

    /** 学习时写入的偏好键：{@code <prefix>[long_]<keyCode>}。 */
    static String androidBindingKey(SharedPreferences prefs, int keyCode, boolean longPress) {
        return androidPrefix(prefs) + androidBindingSuffix(keyCode, longPress);
    }

    /** 键名后缀，不含协议前缀，便于离线自测。 */
    static String androidBindingSuffix(int keyCode, boolean longPress) {
        return (longPress ? LONG_SUFFIX : "") + keyCode;
    }

    static int androidCommand(SharedPreferences prefs, int keyCode) {
        return androidCommand(prefs, keyCode, false);
    }

    /**
     * 读取某个 Android 按键绑定的功能。
     *
     * @param longPress {@code true} 读取长按绑定，否则读取单击绑定
     * @return 功能号；未绑定时为 -1
     */
    static int androidCommand(SharedPreferences prefs, int keyCode, boolean longPress) {
        migrate(prefs);
        return prefs.getInt(androidBindingKey(prefs, keyCode, longPress), -1);
    }

    /** 厂家语音键的广播码。 */
    static final String VENDOR_VR = "VR";
    /** 语音键默认：单击返回主页。 */
    static final int DEFAULT_VR_SINGLE = 200;
    /** 语音键默认：长按唤醒 Siri。 */
    static final int DEFAULT_VR_LONG = 10003;

    /** 学习时写入的厂家广播偏好键：{@code steering_cs35plus_vendor_[long_]<code>}。 */
    static String vendorBindingKey(SharedPreferences prefs, String code, boolean longPress) {
        return vendorPrefix() + vendorBindingSuffix(code, longPress);
    }

    /** 厂家广播键名后缀，不含协议前缀，便于离线自测。 */
    static String vendorBindingSuffix(String code, boolean longPress) {
        return (longPress ? LONG_SUFFIX : "") + code;
    }

    static int vendorCommand(SharedPreferences prefs, String code) {
        return vendorCommand(prefs, code, false);
    }

    /**
     * 读取厂家广播按键绑定的功能。
     *
     * <p>语音键（{@link #VENDOR_VR}）未学习时使用默认值：单击返回主页、长按唤醒 Siri，
     * 可用「方控学习」覆盖。其他键保持原有行为（未学习即不响应）。
     *
     * @param longPress {@code true} 读取长按绑定，否则读取单击绑定
     * @return 功能号；未绑定且无默认值时为 -1
     */
    static int vendorCommand(SharedPreferences prefs, String code, boolean longPress) {
        migrate(prefs);
        if (!vendorEnabled(prefs)) return -1;
        int learned = prefs.getInt(vendorBindingKey(prefs, code, longPress), -1);
        if (learned >= 0) return learned;
        if (VENDOR_VR.equals(code)) {
            return longPress ? DEFAULT_VR_LONG : DEFAULT_VR_SINGLE;
        }
        return -1;
    }
}
