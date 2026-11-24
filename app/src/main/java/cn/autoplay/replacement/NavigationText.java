package cn.autoplay.replacement;

import org.json.JSONObject;

/**
 * 导航文字状态（{@code CMD_DASHBOARD_DATA 42} 子类型 {@code 200} 的增量 JSON）。
 *
 * <p>盒子每次只下发<b>发生变化的字段</b>，例如每约 0.5 s 来一条
 * {@code {"NaviRemainDistance":53}}，路名变化时才发 {@code NaviRoadName}。
 * 因此这里必须做<b>字段级合并</b>：{@code -1}（整数）或空串（字符串）表示
 * 「本次未更新」，要保留旧值。
 *
 * <p>{@code NaviStatus} 语义：{@code >0} 导航中（实测 {@code 2}）、{@code 0}
 * 导航结束、{@code -1} 本次未更新。实测导航刚建立时盒子会在 {@code 2} 与
 * {@code 0} 之间快速跳变，直接按 0 清空会让副屏闪烁，所以清空要防抖：
 * {@link #shouldShow(long)} 只在超过 {@link #CLEAR_DEBOUNCE_MS} 没有有效数据时
 * 才返回 {@code false}。
 *
 * <p>本类不依赖任何 Android API，便于在 {@code ProtocolFrameSelfTest} 里直接跑。
 */
final class NavigationText {
    /** 收到 {@code NaviStatus=0} 后，多久没有新的有效数据才真正清空。 */
    static final long CLEAR_DEBOUNCE_MS = 3000L;

    private String road = "";
    private String destination = "";
    private int turnDistance = -1;
    private int destinationDistance = -1;
    private int destinationSeconds = -1;
    private int turnAngle = -1;
    private int turnSide = -1;
    private int maneuverType = -1;
    private int orderTypeNew = -1;
    private int naviStatus = -1;
    private String status = "等待盒子上报导航数据";

    /** 最近一次有效数据/导航开始的时间（毫秒）。 */
    private long lastActiveAt;
    /** 已收到 {@code NaviStatus=0}，等待防抖结束再清空。 */
    private boolean endPending;

    void clear(String message) {
        road = destination = "";
        turnDistance = destinationDistance = destinationSeconds = -1;
        turnAngle = turnSide = maneuverType = orderTypeNew = -1;
        naviStatus = -1;
        endPending = false;
        lastActiveAt = 0L;
        status = message;
    }

    /** 收到 {@code CMD_CARPLAY_CMD 8 / 506}（盒子通知导航开始）时调用。 */
    void markStarted(long now) {
        endPending = false;
        lastActiveAt = now;
    }

    /** 收到 {@code 507}（盒子通知导航结束）时调用；同样走防抖。 */
    void markEnded(long now) {
        endPending = true;
        if (lastActiveAt == 0L) lastActiveAt = now;
        status = "导航已结束";
    }

    /** 当前是否还有待清空的结束标记（用于安排防抖刷新）。 */
    boolean endPending() {
        return endPending;
    }

    void update(JSONObject data) {
        update(data, System.currentTimeMillis());
    }

    /**
     * 合并一包导航增量数据。
     *
     * <p>字段级容错：某个字段类型不对或超出范围时只跳过该字段，不影响同一包内
     * 其他字段（盒子偶尔会把整数发成字符串）。
     *
     * @param now 用于防抖的时间戳，测试可注入
     */
    void update(JSONObject data, long now) {
        if (data == null) return;

        boolean changed = false;
        String nextRoad = string(data, "NaviRoadName", road);
        if (!nextRoad.equals(road)) {
            road = nextRoad;
            changed = true;
        }
        String nextDestination = string(data, "NaviDestinationName", destination);
        if (!nextDestination.equals(destination)) {
            destination = nextDestination;
            changed = true;
        }
        int next = number(data, "NaviRemainDistance", turnDistance);
        if (next != turnDistance) {
            turnDistance = next;
            changed = true;
        }
        next = number(data, "NaviDistanceToDestination", destinationDistance);
        if (next != destinationDistance) {
            destinationDistance = next;
            changed = true;
        }
        next = number(data, "NaviTimeToDestination", destinationSeconds);
        if (next != destinationSeconds) {
            destinationSeconds = next;
            changed = true;
        }
        next = number(data, "NaviTurnAngle", turnAngle);
        if (next != turnAngle) {
            turnAngle = next;
            changed = true;
        }
        next = number(data, "NaviTurnSide", turnSide);
        if (next != turnSide) {
            turnSide = next;
            changed = true;
        }
        next = number(data, "NaviManeuverType", maneuverType);
        if (next != maneuverType) {
            maneuverType = next;
            changed = true;
        }
        next = number(data, "NaviOrderTypeNew", orderTypeNew);
        if (next != orderTypeNew) {
            orderTypeNew = next;
            changed = true;
        }

        int nextStatus = number(data, "NaviStatus", naviStatus);
        if (nextStatus != naviStatus) {
            naviStatus = nextStatus;
            if (naviStatus > 0) {
                endPending = false;
                changed = true;
            } else if (naviStatus == 0) {
                markEnded(now);
            }
        }

        // 包里只要还有道路/距离/转向等实际导航字段，就说明导航仍在进行。
        //
        // 实测盒子在建链瞬间会连发 NaviStatus 0/2 交替（61 ms 内 7 条）。若最后
        // 落地的是 0，endPending 会一直为 true；而下面刻意在 endPending 时不刷新
        // lastActiveAt，于是 shouldShow() 在 3 秒后返回 false，导航区永久隐藏 ——
        // 即使 42/200 一直在流，也要等盒子再发一次 NaviStatus>0 才恢复。
        boolean content = hasNavigationContent(data);
        if (content) {
            endPending = false;
        }

        if (changed || content) {
            if (endPending) {
                status = "导航已结束";
            } else {
                lastActiveAt = now;
                status = "等待道路及距离信息";
            }
        } else if (!endPending && naviStatus != 0) {
            // 内容未变（例如车辆静止、距离不变）但仍在上报数据，同样算「活跃」，
            // 否则一次误发的 NaviStatus=0 会在 3 秒后把画面清掉。
            lastActiveAt = now;
        }
    }

    /** 除 {@code NaviStatus} 之外的导航内容字段（出现任意一个即视为导航进行中）。 */
    private static final String[] CONTENT_KEYS = {
            "NaviRoadName", "NaviRemainDistance", "NaviTurnAngle", "NaviTurnSide",
            "NaviManeuverType", "NaviOrderType", "NaviOrderTypeNew",
            "NaviNextTurnTimeSeconds", "NaviTimeToDestination",
            "NaviDistanceToDestination", "NaviDestinationName",
    };

    /** 该包是否携带实际导航内容（区别于只有 {@code NaviStatus} 的状态包）。 */
    static boolean hasNavigationContent(JSONObject data) {
        for (String key : CONTENT_KEYS) {
            if (data.has(key)) {
                return true;
            }
        }
        return false;
    }

    /** 是否有可显示的内容（不代表当前应显示，需再看 {@link #shouldShow(long)}）。 */
    boolean hasContent() {
        return turnDistance >= 0 || destinationSeconds >= 0 || destinationDistance >= 0
                || !road.isEmpty() || !destination.isEmpty();
    }

    /** 当前是否应该显示导航区（含 {@code NaviStatus=0} 的 3 秒防抖）。 */
    boolean shouldShow(long now) {
        return hasContent() && !(endPending && now - lastActiveAt > CLEAR_DEBOUNCE_MS);
    }

    /** 第一行：{@code "44m 进入 中贸街"}。 */
    String primaryLine() {
        StringBuilder text = new StringBuilder();
        if (turnDistance >= 0) text.append(distance(turnDistance)).append(' ');
        if (!road.isEmpty()) text.append(road);
        return text.toString().trim();
    }

    /** 第二行：{@code "22分钟 | 8.6km"}。 */
    String secondaryLine() {
        if (destinationSeconds >= 0 && destinationDistance >= 0) {
            return duration(destinationSeconds) + " | " + distance(destinationDistance);
        }
        if (destinationSeconds >= 0) return duration(destinationSeconds);
        if (destinationDistance >= 0) return distance(destinationDistance);
        if (!destination.isEmpty()) return destination;
        return "";
    }

    /**
     * 当前转向类型，用于绘制箭头。
     *
     * <p>优先用 {@code NaviOrderTypeNew}（高德转向图标编号，见 {@link Maneuver}）；
     * 该字段缺失时才退化到 {@code NaviTurnAngle}（真实角度，需足够大才有意义）
     * 与 {@code NaviTurnSide}。
     *
     * <p>注意 {@code NaviTurnAngle} 实测常只有 {@code 1~4}，是道路走向的微小变化，
     * <b>不能</b>当作转向分类使用。
     */
    Maneuver maneuver() {
        if (orderTypeNew >= 0) {
            return Maneuver.fromOrderTypeNew(orderTypeNew, turnSide);
        }
        if (turnAngle > 15 && turnAngle <= 180) {
            return turnAngle > 110 ? Maneuver.SHARP_RIGHT
                    : turnAngle > 60 ? Maneuver.RIGHT : Maneuver.SLIGHT_RIGHT;
        }
        if (turnAngle >= 180 && turnAngle <= 345) {
            float left = 360f - turnAngle;
            return left > 110 ? Maneuver.SHARP_LEFT
                    : left > 60 ? Maneuver.LEFT : Maneuver.SLIGHT_LEFT;
        }
        // TurnSide 的左右语义存疑（两个样本互相矛盾，见 Maneuver 类注释），
        // 这里保持历史行为，只在 OrderTypeNew 缺失时兜底。
        if (turnSide == 1) return Maneuver.RIGHT;
        if (turnSide == 2) return Maneuver.LEFT;
        return Maneuver.STRAIGHT;
    }

    /** 原始字段 + 解析结果的单行摘要，供设置页对照车机屏幕排查图标问题。 */
    String debugLine() {
        return "OrderTypeNew=" + orderTypeNew
                + " · TurnAngle=" + turnAngle
                + " · TurnSide=" + turnSide
                + " · ManeuverType=" + maneuverType
                + " → " + maneuver().label();
    }

    /** 多行文本，用于「仪表导航」设置页与日志。 */
    String text() {
        StringBuilder text = new StringBuilder("导航");
        String primary = primaryLine();
        String secondary = secondaryLine();
        if (!primary.isEmpty()) text.append('\n').append(primary);
        if (!secondary.isEmpty()) text.append('\n').append(secondary);
        if (!destination.isEmpty() && !destination.equals(secondary)) {
            text.append("\n目的地：").append(destination);
        }
        if (primary.isEmpty() && secondary.isEmpty()) {
            text.append('\n').append(status);
        }
        return text.toString();
    }

    /** 距离格式化，与原版 {@code util/n0.b(int)} 一致。 */
    static String distance(int meters) {
        if (meters < 0) return "";
        if (meters < 1000) return meters + "m";
        double km = meters / 1000.0;
        int whole = (int) km;
        if (km == whole) return whole + "km";
        return String.format(java.util.Locale.ROOT, "%.1fkm", km);
    }

    /** 时间格式化，与原版 {@code util/n0.c(int)} 一致。 */
    static String duration(int seconds) {
        if (seconds < 0) return "";
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) {
            int minutes = seconds / 60;
            int rest = seconds % 60;
            return rest == 0 ? minutes + "分钟" : minutes + "分钟" + rest + "秒";
        }
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int rest = seconds % 60;
        StringBuilder text = new StringBuilder(hours + "小时");
        if (minutes > 0) text.append(minutes).append("分钟");
        if (rest > 0) text.append(rest).append("秒");
        return text.toString();
    }

    // ---- 容错解析 ----

    /** 空串/非字符串视为「未更新」，保留旧值。 */
    private static String string(JSONObject data, String key, String previous) {
        if (!data.has(key)) return previous;
        Object value = data.opt(key);
        if (!(value instanceof String)) return previous;
        String text = ((String) value).trim();
        return text.isEmpty() ? previous : text;
    }

    /** {@code -1}、非数字、超出 int 范围都视为「未更新」，保留旧值。 */
    private static int number(JSONObject data, String key, int previous) {
        if (!data.has(key)) return previous;
        Object value = data.opt(key);
        double parsed;
        if (value instanceof Number) {
            parsed = ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                parsed = Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException exception) {
                return previous;
            }
        } else {
            return previous;
        }
        if (Double.isNaN(parsed) || parsed < -1 || parsed > Integer.MAX_VALUE
                || parsed != Math.rint(parsed)) {
            return previous;
        }
        return parsed == -1 ? previous : (int) parsed;
    }
}
