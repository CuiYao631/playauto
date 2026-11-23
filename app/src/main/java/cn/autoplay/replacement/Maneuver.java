package cn.autoplay.replacement;

/**
 * 导航转向类型。
 *
 * <p>来自 {@code cmd 42 / 200} 的 {@code NaviOrderTypeNew}。实测（盒子固件
 * {@code 2026.07.31.1733}，CarPlay 链路）取值与小端枚举完全对应 Apple CarPlay 的
 * {@code CPManeuverType}：
 *
 * <pre>
 * 0  无转向     1  直行       2  稍向右     3  右转       4  急右转
 * 5  掉头       6  急左转     7  左转       8  稍向左     9  到达目的地
 * 10 到达目的地左 11 到达目的地右
 * 12 出发       13 出发左     14 出发右
 * 15~18 环岛（进入 / 驶出 / 驶出左 / 驶出右）
 * </pre>
 *
 * <p>各次实抓验证：{@code 7} 对应「左转」、{@code 4} 对应「急右转」、{@code 8}
 * 对应「稍向左」、{@code 2} 对应「稍向右」，与车机屏幕上的提示一致。
 *
 * <p>{@code NaviTurnAngle} 不要当角度用：实测只有 {@code 1~4}，是道路走向的微小
 * 变化（度），不是转向分类；{@code NaviTurnSide} 才是方向（实测 {@code 1}=右、
 * {@code 2}=左），仅在 {@code NaviOrderTypeNew} 缺失时作为兜底。
 */
enum Maneuver {
    STRAIGHT(0f, "直行"),
    SLIGHT_RIGHT(38f, "稍向右"),
    RIGHT(90f, "右转"),
    SHARP_RIGHT(138f, "急右转"),
    UTURN(180f, "掉头"),
    SHARP_LEFT(-138f, "急左转"),
    LEFT(-90f, "左转"),
    SLIGHT_LEFT(-38f, "稍向左"),
    ARRIVE(0f, "到达目的地"),
    START(0f, "出发"),
    ROUNDABOUT(0f, "环岛"),
    UNKNOWN(0f, "未知转向");

    /** 箭头绕竖直方向弯曲的角度（度，顺时针为正），供绘图用。 */
    private final float degrees;
    private final String label;

    Maneuver(float degrees, String label) {
        this.degrees = degrees;
        this.label = label;
    }

    float degrees() {
        return degrees;
    }

    String label() {
        return label;
    }

    /**
     * 由 {@code NaviOrderTypeNew} 映射；未知取值时退化到 {@code NaviTurnSide}。
     *
     * @param orderTypeNew 实测 {@code -1} 表示本包未下发
     * @param turnSide     实测 {@code 1}=右、{@code 2}=左，{@code -1} 表示未下发
     */
    static Maneuver fromOrderTypeNew(int orderTypeNew, int turnSide) {
        switch (orderTypeNew) {
            case 0:
            case 1:
                return STRAIGHT;
            case 2:
                return SLIGHT_RIGHT;
            case 3:
                return RIGHT;
            case 4:
                return SHARP_RIGHT;
            case 5:
                return UTURN;
            case 6:
                return SHARP_LEFT;
            case 7:
                return LEFT;
            case 8:
                return SLIGHT_LEFT;
            case 9:
            case 10:
            case 11:
                return ARRIVE;
            case 12:
            case 13:
            case 14:
                return START;
            case 15:
            case 16:
            case 17:
            case 18:
                return ROUNDABOUT;
            case 999:
                return UNKNOWN;
            default:
                break;
        }
        // 未知/缺失：按方向兜底，比画成直行更接近实际
        if (turnSide == 1) return RIGHT;
        if (turnSide == 2) return LEFT;
        return UNKNOWN;
    }
}
