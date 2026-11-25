package cn.autoplay.replacement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把盒子下发的 {@code CMD 42 / 200} 导航数据自动转成仪表广播（规范 §4.1）。
 *
 * <p>{@link ClusterNavi} 只负责"按规范拼报文"，本类负责把盒子的事件流翻译成
 * ①②③④：何时开始、转向图标怎么映射、什么情况下值得发、什么情况下必须收尾。
 *
 * <p><b>四条硬约束</b>：
 *
 * <ul>
 *   <li><b>每次都先补 ①</b>：车机仪表可能刚上电/刚重启，错过最初那次 ① 就永远不显示。
 *       ① 是幂等的状态设置，重复发无副作用。</li>
 *   <li><b>节流</b>：盒子每约 0.5 s 一条增量包，逐包转发会在车总线上刷屏。
 *       这里限制到 {@link #MIN_INTERVAL_MS}，且内容没变就不发。</li>
 *   <li><b>②③ 成对</b>：规范 §4.1 明确只发 ② 仪表不会更新，所以
 *       {@link #onNavigationUpdate} 永远同时产出 ② 和 ③。</li>
 *   <li><b>④ 必须先发 {@code NAVIGATION_STOPPED}</b>：只发结束状态不会清图标。</li>
 * </ul>
 *
 * <p>盒子断开（拔线、USB 抖动、会话异常结束）时必须调用 {@link #onNavigationEnded()}，
 * 否则仪表会永远停在最后一个图标上。
 *
 * <p>本类不依赖 Android API，便于在 {@code ProtocolFrameSelfTest} 里直接跑。
 */
final class ClusterNaviBridge {
    /** 两次下发的最大频率：1 秒 1 次。 */
    static final long MIN_INTERVAL_MS = 1000L;

    private final ClusterNavi navi;

    /** 上次下发的内容，用于"没变化就不发"。 */
    private int lastIconId = -1;
    private int lastDistance = -1;
    private int lastRemainMeters = -1;
    private int lastRemainSeconds = -1;
    private String lastRoad = "";
    private long lastSentAt;

    /** 最近一次已知的转向图标；{@code -1}（本包未更新）时沿用它，避免擦掉已显示的图标。 */
    private int lastKnownIcon = ClusterNaviIcons.ID_STRAIGHT;

    ClusterNaviBridge(ClusterNavi navi) {
        this.navi = navi;
    }

    boolean isActive() {
        return navi.isActive();
    }

    /** 丢弃全部状态（会话断开/重连）。不会产生结束广播。 */
    void reset() {
        navi.reset();
        lastIconId = -1;
        lastDistance = -1;
        lastRemainMeters = -1;
        lastRemainSeconds = -1;
        lastRoad = "";
        lastSentAt = 0L;
        lastKnownIcon = ClusterNaviIcons.ID_STRAIGHT;
    }

    /**
     * 一包导航增量数据 → 需要下发的广播。
     *
     * <p><b>每次都先补 ①</b>：车机仪表可能刚上电、刚重启，或在上一轮会话里掉线，
     * 错过最初那一次 ① 之后即使 ②③ 一直发也不会显示。多发的 ① 是幂等的状态设置，
     * 原车收到 {@code EXTRA_STATE=8} 只是"置为导航中"。
     *
     * @param now 当前时间（毫秒），测试可注入
     * @return 待发送的消息；无需发送时为空列表
     */
    List<ClusterNavi.Message> onNavigationUpdate(NavigationText text, long now) {
        if (text == null || !text.hasContent()) {
            return Collections.emptyList();
        }
        int iconId = iconIdFor(text.orderTypeNew());
        // 转向距离未下发时不能编一个出来：盒子是增量包，某些包只带剩余里程/时间。
        // 没有已知的转向距离就只发 ①，等真实值到了再补 ②③。
        int turnDistance = text.turnDistance();
        if (turnDistance < 0) {
            turnDistance = lastDistance;
        }
        int distance = turnDistance < 0 ? -1 : clampDistance(turnDistance);
        int remainMeters = Math.max(0, text.destinationDistance());
        int remainSeconds = Math.max(0, text.destinationSeconds());
        String road = text.roadName();

        // !navi.isActive() 也要算"有变化"：别处（设置页 ④、Activity 销毁）可能已把
        // 状态机 stop 掉，此时下一包必须重新发 ① 才能重新接上仪表。
        boolean changed = !navi.isActive()
                || iconId != lastIconId
                || distance != lastDistance
                || remainMeters != lastRemainMeters
                || remainSeconds != lastRemainSeconds
                || !road.equals(lastRoad);
        if (!changed || now - lastSentAt < MIN_INTERVAL_MS) {
            return Collections.emptyList();
        }

        List<ClusterNavi.Message> messages = new ArrayList<>(3);
        // ① 每次重发：仪表可能没接上过
        messages.addAll(navi.start(true));
        if (distance >= 0) {
            messages.addAll(navi.turn(iconId, distance));
        }
        messages.addAll(navi.flush(remainMeters, remainSeconds,
                remainText(remainSeconds), 0, road, ""));

        lastIconId = iconId;
        if (distance >= 0) {
            lastDistance = distance;
        }
        lastRemainMeters = remainMeters;
        lastRemainSeconds = remainSeconds;
        lastRoad = road;
        lastSentAt = now;
        return messages;
    }

    /**
     * 导航结束 → ④。
     *
     * <p>盒子可能不发 507，{@code NaviStatus=0} 也可能被 {@link NavigationText}
     * 的防抖吞掉，所以调用方应在"确认真的结束"时调用；未开始时返回空列表。
     */
    List<ClusterNavi.Message> onNavigationEnded() {
        List<ClusterNavi.Message> messages = navi.stop();
        lastIconId = -1;
        lastDistance = -1;
        lastRemainMeters = -1;
        lastRemainSeconds = -1;
        lastRoad = "";
        lastSentAt = 0L;
        lastKnownIcon = ClusterNaviIcons.ID_STRAIGHT;
        return messages;
    }

    /** {@code -1}（本包未更新）沿用上一次的图标，避免把已显示的图标擦掉。 */
    private int iconIdFor(int orderTypeNew) {
        if (orderTypeNew > 0) {
            lastKnownIcon = iconIdForOrderType(orderTypeNew);
        }
        return lastKnownIcon;
    }

    /**
     * 高德转向图标编号（{@code NaviOrderTypeNew}）→ 仪表 GAODE ID。
     *
     * <p>两套编号不是同一个空间：盒子给的是手机导航的转向类型（1~72），
     * 仪表要的是原车映射表里的 {@code GAODE ID}（见 {@link ClusterNaviIcons}）。
     * 映射依据 {@link Maneuver} 的分类，不引入新猜测。
     */
    static int iconIdForOrderType(int orderTypeNew) {
        // 环岛各出口 11~18 → 第 1~8 出口（235 起每档 +4）
        if (orderTypeNew >= 11 && orderTypeNew <= 18) {
            return ClusterNaviIcons.ID_ROUNDABOUT_EXIT_1 + (orderTypeNew - 11) * 4;
        }
        // 左转分级 / 右转分级沿用基础箭头：仪表没有细分图形
        if (orderTypeNew >= 31 && orderTypeNew <= 39) return ClusterNaviIcons.ID_LEFT;
        if (orderTypeNew >= 40 && orderTypeNew <= 51) return ClusterNaviIcons.ID_RIGHT;
        // 匝道 / 出口：靠右驶出比"环岛出口"更贴近实际
        if (orderTypeNew >= 20 && orderTypeNew <= 22) return ClusterNaviIcons.ID_KEEP_RIGHT;
        switch (orderTypeNew) {
            case 1:
            case 8:  return ClusterNaviIcons.ID_STRAIGHT;
            case 2:  return ClusterNaviIcons.ID_LEFT;
            case 3:  return ClusterNaviIcons.ID_RIGHT;
            case 4:  return ClusterNaviIcons.ID_KEEP_LEFT;
            case 5:  return ClusterNaviIcons.ID_KEEP_RIGHT;
            case 6:  return ClusterNaviIcons.ID_REAR_LEFT;
            case 7:  return ClusterNaviIcons.ID_REAR_RIGHT;
            case 9:  return ClusterNaviIcons.ID_KEEP_LEFT;
            case 10: return ClusterNaviIcons.ID_KEEP_RIGHT;
            // 左舵车按左掉头显示；右掉头（ID 24）留给右舵车型
            case 19: return ClusterNaviIcons.ID_UTURN_LEFT;
            default: break;
        }
        // 52~72 复杂路口不带方向（见 Maneuver 类注释），999 未命中：
        // 一律退回直行箭头，比乱猜左右安全
        return ClusterNaviIcons.ID_STRAIGHT;
    }

    /** 仪表要求 {@code 1..65534} 米。 */
    static int clampDistance(int meters) {
        if (meters < ClusterNavi.MIN_DISTANCE) return ClusterNavi.MIN_DISTANCE;
        if (meters > ClusterNavi.MAX_DISTANCE) return ClusterNavi.MAX_DISTANCE;
        return meters;
    }

    /** {@code ROUTE_REMAIN_TIME_STRING}：原车只做展示，给个短文本即可。 */
    static String remainText(int seconds) {
        if (seconds <= 0) return "";
        if (seconds < 60) return "1 min";
        return (seconds / 60) + " min";
    }
}
