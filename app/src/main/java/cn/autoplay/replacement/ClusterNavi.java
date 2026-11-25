package cn.autoplay.replacement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把导航数据投到<b>原车仪表</b>的广播协议（规范见 {@code docs/仪表导航控制说明.md}）。
 *
 * <p>原车 {@code DualScreenService} / {@code TDualService} 动态注册了高德导航广播的接收器，
 * 普通应用用 {@link #ACT_STANDARD} 等广播就能驱动仪表，<b>不需要 root、平台签名或系统 UID</b>。
 *
 * <p><b>消息序列（必须严格遵守，见规范 §4.1）</b>：
 *
 * <pre>
 * ① start   开始导航              KEY_TYPE=10019, EXTRA_STATE=8
 * ② turn    图标 + 路口距离        NAVIGATION_UPDATES（只更新缓存）
 * ③ flush   触发提交              KEY_TYPE=10001 + 摘要字段
 * ④ stop    停止 + 结束            NAVIGATION_STOPPED → KEY_TYPE=10019, EXTRA_STATE=9
 * </pre>
 *
 * ②③ 必须成对：<b>只发 ② 不发 ③，仪表不会更新</b>。换图标或距离后要重新走 ②③。
 *
 * <p>本类只构造消息、不发送，便于在纯 JVM 下用 {@code ProtocolFrameSelfTest} 断言
 * （规范 §10.1）。发送与 Intent 组装由 {@code SettingsScreens} 负责。
 *
 * <p>状态机（规范 §8）：{@code IDLE →(start)→ ACTIVE →(stop)→ IDLE}。
 * 未 {@code start()} 的更新一律丢弃；重复 {@code stop()} 幂等且不再产生广播。
 */
final class ClusterNavi {
    static final String ACT_STANDARD = "AUTONAVI_STANDARD_BROADCAST_SEND";
    static final String ACT_UPDATE = "com.autonavi.navigation.NAVIGATION_UPDATES";
    static final String ACT_STOPPED = "com.autonavi.navigation.NAVIGATION_STOPPED";

    /** 开始 / 结束状态消息的类型码。 */
    static final int KEY_TYPE_STATE = 10019;
    /** 摘要（触发提交）的类型码。 */
    static final int KEY_TYPE_SUMMARY = 10001;
    static final int EXTRA_STATE_START = 8;
    static final int EXTRA_STATE_END = 9;

    /** 路口距离上下限（规范 §10.1 第 3 条）：0、65535、-1 都非法。 */
    static final int MIN_DISTANCE = 1;
    static final int MAX_DISTANCE = 65534;

    /** {@code nextRoadName} 在报文里的容量，见规范 §7。 */
    static final int ROAD_NAME_CAPACITY = 63;

    private boolean active;

    /** 是否处于「已开始、未结束」状态。 */
    boolean isActive() {
        return active;
    }

    /** 仅用于界面在重建页面时还原状态。 */
    void reset() {
        active = false;
    }

    /**
     * ① 开始导航。
     *
     * @param force 为 {@code true} 时即使已经 ACTIVE 也重发
     *              （车机仪表可能刚上电，错过了最初那一次就永远不会显示）
     * @return 待发送的消息；已 ACTIVE 且未强制重发时返回空列表
     */
    List<Message> start(boolean force) {
        boolean alreadyActive = active;
        active = true;
        if (alreadyActive && !force) {
            return Collections.emptyList();
        }
        return Collections.singletonList(stateMessage(EXTRA_STATE_START));
    }

    /**
     * ① 开始导航（已 ACTIVE 时不重复发送）。
     *
     * @return 待发送的消息；重复调用返回空列表（already active）
     */
    List<Message> start() {
        return start(false);
    }

    /**
     * ② 更新图标与路口距离（只更新原车缓存，不提交）。
     *
     * @param iconId   {@link ClusterNaviIcons} 里的高德图标 ID
     * @param distance 路口距离，单位米，必须在 {@code 1..65534}
     * @throws IllegalArgumentException 图标不支持或距离越界（规范禁止扫未知 ID）
     */
    List<Message> turn(int iconId, int distance) {
        if (!active) {
            return Collections.emptyList();
        }
        if (ClusterNaviIcons.find(iconId) == null) {
            throw new IllegalArgumentException("不支持的仪表图标 ID：" + iconId);
        }
        if (distance < MIN_DISTANCE || distance > MAX_DISTANCE) {
            throw new IllegalArgumentException(
                    "路口距离必须在 " + MIN_DISTANCE + "~" + MAX_DISTANCE + " 米：" + distance);
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("navigation_id", iconId);
        // 必须是 "<数值>,<单位>" 字符串；传整数或 "-1" 会被原车直接过滤
        extras.put("navigation_dis", distance + ",m");
        return Collections.singletonList(new Message(ACT_UPDATE, extras));
    }

    /**
     * ③ 发送摘要，触发原车把缓存提交给 MCU。
     *
     * <p>不复用 {@link #turn} 的距离缓存是刻意的：规范要求 ②③ 显式成对，
     * 隐式缓存会让「漏发 ③」这类问题更难发现。
     *
     * @param remainMeters  剩余总里程（米），负数按 0
     * @param remainSeconds 剩余时间（<b>秒</b>），负数按 0
     * @param remainText    展示文本，如 {@code "10 min"}，可为 {@code null}
     * @param totalMeters   全程里程（米），负数按 0
     * @param currentRoad   当前道路名，可为 {@code null}
     * @param nextRoad      下一道路名，可为 {@code null}
     */
    List<Message> flush(int remainMeters, int remainSeconds, String remainText,
            int totalMeters, String currentRoad, String nextRoad) {
        if (!active) {
            return Collections.emptyList();
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("KEY_TYPE", KEY_TYPE_SUMMARY);
        extras.put("ROUTE_REMAIN_DIS", Math.max(0, remainMeters));
        extras.put("ROUTE_REMAIN_TIME", Math.max(0, remainSeconds));
        extras.put("ROUTE_REMAIN_TIME_STRING", remainText == null ? "" : remainText);
        extras.put("ROUTE_ALL_DIS", Math.max(0, totalMeters));
        extras.put("CUR_ROAD_NAME", currentRoad == null ? "" : currentRoad);
        extras.put("NEXT_ROAD_NAME", nextRoad == null ? "" : nextRoad);
        extras.put("LIMITED_SPEED", 0);
        extras.put("ROAD_TYPE", 0);
        return Collections.singletonList(new Message(ACT_STANDARD, extras));
    }

    /**
     * ④ 结束导航。
     *
     * <p>规范 §8.3：必须先发 {@code NAVIGATION_STOPPED}（清转向码并立即提交），
     * 再发结束状态；<b>只发结束状态不会清除仪表上的图标</b>。
     *
     * @return 两条消息；未开始时返回空列表（幂等，避免无意义刷写）
     */
    List<Message> stop() {
        if (!active) {
            return Collections.emptyList();
        }
        active = false;
        List<Message> messages = new ArrayList<>(2);
        messages.add(new Message(ACT_STOPPED, new LinkedHashMap<>()));
        messages.add(stateMessage(EXTRA_STATE_END));
        return messages;
    }

    private static Message stateMessage(int extraState) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("KEY_TYPE", KEY_TYPE_STATE);
        extras.put("EXTRA_STATE", extraState);
        return new Message(ACT_STANDARD, extras);
    }

    /**
     * 按字节容量截断 UTF-8 文本，保证不切断多字节字符（规范 §7）。
     *
     * <p>不足部分补 {@code 0x00}；{@code null} / 空串返回全 0。
     */
    static byte[] utf8Clamped(String text, int capacity) {
        byte[] out = new byte[capacity];
        if (text == null || text.isEmpty() || capacity <= 0) {
            return out;
        }
        byte[] all = text.getBytes(StandardCharsets.UTF_8);
        if (all.length <= capacity) {
            System.arraycopy(all, 0, out, 0, all.length);
            return out;
        }
        int end = capacity;
        // 0b10xxxxxx 是 UTF-8 续字节；回退到字符起始边界
        while (end > 0 && (all[end] & 0xC0) == 0x80) {
            end--;
        }
        System.arraycopy(all, 0, out, 0, end);
        return out;
    }

    /** 一条待发送的广播。{@code extras} 的值只有 {@link Integer} 与 {@link String} 两种。 */
    static final class Message {
        final String action;
        final Map<String, Object> extras;

        Message(String action, Map<String, Object> extras) {
            this.action = action;
            this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(extras));
        }

        /** 组装 Intent 时必须用基本类型 {@code int}，不能传装箱对象（规范 §2.2）。 */
        boolean hasInt(String key) {
            return extras.get(key) instanceof Integer;
        }

        @Override
        public String toString() {
            return action + " " + extras;
        }
    }

    /** 便于测试与界面展示：把一批消息写成人可读的多行文本。 */
    static String describe(List<Message> messages) {
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(message);
        }
        return text.toString();
    }

    /** 依次追加，便于把 {@link #turn} 与 {@link #flush} 合并成一次点击（②③ 成对发送）。 */
    @SafeVarargs
    static List<Message> combine(List<Message>... groups) {
        List<Message> all = new ArrayList<>();
        for (List<Message> group : groups) {
            all.addAll(group);
        }
        return Collections.unmodifiableList(all);
    }
}
