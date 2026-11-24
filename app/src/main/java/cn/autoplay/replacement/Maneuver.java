package cn.autoplay.replacement;

/**
 * 导航转向类型。
 *
 * <p>由 {@code cmd 42 / 200} 的 {@code NaviOrderTypeNew} 映射而来。该字段用的是
 * <b>高德导航的转向图标编号</b>（盒子把手机端导航 App 的数据原样转发），不是 Apple
 * CarPlay 的 {@code CPManeuverType}：
 *
 * <pre>
 * 1        直行（默认）        11~18  环岛各出口
 * 2        左转                19     掉头
 * 3        右转                20~22  匝道 / 出口
 * 4        左前方              31~39  左转分级
 * 5        右前方              40~51  右转分级
 * 6/7/8    左后 / 右后 / 直行变体  52~72  复杂路口
 * 9/10     靠左 / 靠右          999    未命中 → 默认箭头
 * </pre>
 *
 * <p><b>2026-11-24 实抓对照</b>（高德 + 固件 {@code 2026.07.31.1733OHY}）：
 * {@code 7}（右后）出现在「进入 长安北路辅路」、{@code 8}（直行变体）出现在
 * 「进入 中贸街 / 无名道路」、{@code 2}（左转）出现在「进入 中贸街」。
 * 按旧版 CarPlay 编码解析会把这三者画成左转 / 稍向左 / 稍向右，方向全错。
 *
 * <p>{@code NaviTurnAngle} 不要当角度用：实测只有 {@code 1~4}，是道路走向的微小
 * 变化（度），不是转向分类。
 *
 * <p>{@code NaviTurnSide} 的左右语义存疑，只在 {@code NaviOrderTypeNew} 缺失时兜底：
 * 早期样本（{@code OrderTypeNew=3} + {@code TurnSide=1}）指向 {@code 1=右}，
 * 2026-11-24 样本（{@code OrderTypeNew=2}=左转 + {@code TurnSide=1}）指向
 * {@code 1=左}，两者互相矛盾，故主映射完全不依赖该字段。
 */
enum Maneuver {
    STRAIGHT(0f, "直行"),
    KEEP_LEFT(-20f, "靠左"),
    KEEP_RIGHT(20f, "靠右"),
    SLIGHT_LEFT(-38f, "稍向左"),
    SLIGHT_RIGHT(38f, "稍向右"),
    LEFT(-90f, "左转"),
    RIGHT(90f, "右转"),
    SHARP_LEFT(-138f, "急左转"),
    SHARP_RIGHT(138f, "急右转"),
    UTURN(180f, "掉头"),
    ARRIVE(0f, "到达目的地"),
    START(0f, "出发"),
    ROUNDABOUT(0f, "环岛"),
    /** 匝道 / 出口：图形与「稍向右」一致，靠 label 区分。 */
    RAMP(38f, "匝道/出口"),
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
     * 由 {@code NaviOrderTypeNew}（高德转向图标编号）映射。
     *
     * @param orderTypeNew 实测 {@code -1} 表示本包未下发
     * @param turnSide     实测 {@code -1} 表示未下发；仅用于表格未覆盖的取值兜底
     */
    static Maneuver fromOrderTypeNew(int orderTypeNew, int turnSide) {
        if (orderTypeNew >= 31 && orderTypeNew <= 39) return LEFT;   // 左转分级
        if (orderTypeNew >= 40 && orderTypeNew <= 51) return RIGHT;  // 右转分级
        if (orderTypeNew >= 11 && orderTypeNew <= 18) return ROUNDABOUT; // 环岛各出口
        if (orderTypeNew >= 20 && orderTypeNew <= 22) return RAMP;   // 匝道 / 出口
        switch (orderTypeNew) {
            case 1:
                return STRAIGHT;
            case 2:
                return LEFT;
            case 3:
                return RIGHT;
            case 4:
                return SLIGHT_LEFT;
            case 5:
                return SLIGHT_RIGHT;
            case 6:
                return SHARP_LEFT;
            case 7:
                return SHARP_RIGHT;
            case 8:
                return STRAIGHT;      // 直行变体
            case 9:
                return KEEP_LEFT;
            case 10:
                return KEEP_RIGHT;
            case 19:
                return UTURN;
            case 999:
                return UNKNOWN;
            default:
                break;
        }
        if (orderTypeNew >= 52 && orderTypeNew <= 72) {
            // 复杂路口：编号不带方向，只能靠 TurnSide 猜（见类注释里的语义冲突）
            if (turnSide == 1) return RIGHT;
            if (turnSide == 2) return LEFT;
            return UNKNOWN;
        }
        if (orderTypeNew >= 1000 && orderTypeNew <= 1003) {
            // AutoKit 的 drawable 键值范围里有 1000~1003，推测为到达/出发一类；
            // 未实抓验证，先按「到达」画，避免落到默认箭头。
            return ARRIVE;
        }
        // 表外取值：按方向兜底，比画成直行更接近实际
        if (turnSide == 1) return RIGHT;
        if (turnSide == 2) return LEFT;
        return UNKNOWN;
    }
}
