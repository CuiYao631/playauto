package cn.autoplay.replacement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 原车仪表支持的转向图标常量表（共 47 项）。
 *
 * <p>数值逐字复制 {@code docs/仪表导航控制说明.md} §6，<b>禁止推断或扩充</b>：
 * 未列出的 ID 一律视为不支持，不允许扫描未知整数。
 *
 * <p>{@code steer1}/{@code steer2} 是经原车 {@code TDualService} 修正后的最终转向码
 * （{@code 0x3C–0x41} 减 5、{@code 0x43}/{@code 0x44} 减 6）。路径 A 只传
 * {@code id}；{@code steer*} 用于核对与将来走 MCU 直连（路径 B）。
 *
 * <p>注意 {@code 0xFF} 是原车映射表的真实输出，<b>不是</b>清除指令——
 * 清除请用 ID {@code 0} 或走结束流程。
 */
final class ClusterNaviIcons {
    /**
     * @param experimental 含义已知，但外观尚未在实车确认（规范 §6.3）
     * @param excluded     {@code null} 表示可用于显示；否则为不在图标选择器出现的原因
     */
    static final class Icon {
        final int id;
        final int steer1;
        final int steer2;
        final String label;
        final boolean experimental;
        final String excluded;

        Icon(int id, int steer1, int steer2, String label, boolean experimental) {
            this(id, steer1, steer2, label, experimental, null);
        }

        Icon(int id, int steer1, int steer2, String label, boolean experimental,
                String excluded) {
            this.id = id;
            this.steer1 = steer1;
            this.steer2 = steer2;
            this.label = label;
            this.experimental = experimental;
            this.excluded = excluded;
        }
    }

    /** 右舵车型专用，左舵车仪表不显示。 */
    private static final String RHD = "右舵专用";
    /** 原车的「未导航」占位图标，导航中不应出现。 */
    private static final String IDLE = "未导航占位";

    // 供 ClusterNaviBridge 引用的具名常量，避免映射逻辑里散落魔数
    static final int ID_CLEAR = 0;
    static final int ID_ARRIVE = 6;
    static final int ID_TUNNEL = 13;
    static final int ID_SERVICE_AREA = 15;
    static final int ID_STRAIGHT = 17;
    static final int ID_KEEP_RIGHT = 21;
    static final int ID_RIGHT = 22;
    static final int ID_REAR_RIGHT = 23;
    static final int ID_UTURN_RIGHT = 24;
    static final int ID_UTURN_LEFT = 25;
    static final int ID_REAR_LEFT = 26;
    static final int ID_LEFT = 27;
    static final int ID_KEEP_LEFT = 28;
    /** 环岛第 1 出口；之后每档 +4（235/239/243…）。 */
    static final int ID_ROUNDABOUT_EXIT_1 = 235;

    /** 47 项，顺序与规范 §6 一致。 */
    static final List<Icon> ALL = Collections.unmodifiableList(Arrays.asList(
            // §6.1 基础方向（已实车验证）
            new Icon(27, 0x13, 0x00, "左转", false),
            new Icon(22, 0x14, 0x00, "右转", false),
            new Icon(17, 0x10, 0x00, "直行", false),
            new Icon(0, 0x00, 0x00, "无导航（清除转向码）", false),
            new Icon(6, 0x03, 0x00, "到达目的地", false),
            new Icon(13, 0x0D, 0x00, "进入隧道", false),
            new Icon(15, 0x0F, 0x00, "服务区", false),
            new Icon(21, 0x11, 0x00, "靠右", false),
            new Icon(28, 0x12, 0x00, "靠左", false),
            new Icon(26, 0x15, 0x00, "左后方", false),
            new Icon(23, 0x16, 0x00, "右后方", false),
            new Icon(25, 0x17, 0x00, "左掉头", false),
            new Icon(24, 0x18, 0x00, "右掉头", false),
            // §6.2 环岛（左舵）
            new Icon(235, 0x2A, 0x00, "环岛第 1 出口驶出", false),
            new Icon(239, 0x2B, 0x00, "环岛第 2 出口驶出", false),
            new Icon(243, 0x2C, 0x00, "环岛第 3 出口驶出", false),
            new Icon(247, 0x2D, 0x00, "环岛第 4 出口驶出", false),
            new Icon(251, 0x2E, 0x00, "环岛第 5 出口驶出", false),
            new Icon(255, 0x2F, 0x00, "环岛第 6 出口驶出", false),
            new Icon(259, 0x30, 0x00, "环岛第 7 出口驶出", false),
            new Icon(263, 0x31, 0x00, "环岛第 8 出口驶出", false),
            new Icon(267, 0x32, 0x00, "环岛第 9 出口驶出", false),
            new Icon(271, 0x33, 0x00, "环岛第 10 出口驶出", false),
            // §6.3 扩展（含义见规范 §6.3，外观未验证）
            new Icon(327, 0x35, 0x00, "驶入环岛", true),
            new Icon(328, 0x36, 0x00, "即将到达途经点", true),
            new Icon(330, 0xFF, 0x01, "驶入环岛（右舵）", false, RHD),
            new Icon(331, 0xFF, 0x04, "前方出口驶出环岛", true),
            new Icon(332, 0x10, 0x03, "沿当前道路长距离直行", true),
            new Icon(333, 0xFF, 0x0F, "即将到达目的地", true),
            new Icon(334, 0xFF, 0x05, "环岛第 1 出口驶出（右舵）", false, RHD),
            new Icon(335, 0xFF, 0x06, "环岛第 2 出口驶出（右舵）", false, RHD),
            new Icon(336, 0xFF, 0x07, "环岛第 3 出口驶出（右舵）", false, RHD),
            new Icon(337, 0xFF, 0x08, "环岛第 4 出口驶出（右舵）", false, RHD),
            new Icon(338, 0xFF, 0x09, "环岛第 5 出口驶出（右舵）", false, RHD),
            new Icon(339, 0xFF, 0x0A, "环岛第 6 出口驶出（右舵）", false, RHD),
            new Icon(340, 0xFF, 0x0B, "环岛第 7 出口驶出（右舵）", false, RHD),
            new Icon(341, 0xFF, 0x0C, "环岛第 8 出口驶出（右舵）", false, RHD),
            new Icon(342, 0xFF, 0x0D, "环岛第 9 出口驶出（右舵）", false, RHD),
            new Icon(343, 0xFF, 0x0E, "环岛第 10 出口驶出（右舵）", false, RHD),
            new Icon(344, 0x37, 0x00, "未导航", false, IDLE),
            new Icon(345, 0x38, 0x00, "含义未提供", true),
            new Icon(346, 0x39, 0x00, "含义未提供", true),
            new Icon(347, 0x3A, 0x00, "含义未提供", true),
            new Icon(348, 0x3B, 0x00, "未导航", false, IDLE),
            new Icon(349, 0x3C, 0x00, "环岛右转", true),
            new Icon(350, 0x3D, 0x00, "环岛左转", true),
            new Icon(351, 0x3E, 0x00, "驶入环岛后调头", true)
    ));

    /** 可在图标选择器里出现的项：排除右舵专用与「未导航」占位。 */
    static final List<Icon> SELECTABLE = Collections.unmodifiableList(filterSelectable());

    private ClusterNaviIcons() {
    }

    private static List<Icon> filterSelectable() {
        List<Icon> selectable = new ArrayList<>(ALL.size());
        for (Icon icon : ALL) {
            if (icon.excluded == null) {
                selectable.add(icon);
            }
        }
        return selectable;
    }

    /**
     * 按 ID 查表。
     *
     * @return 找不到返回 {@code null}（调用方必须拒绝，禁止自行编造编码）
     */
    static Icon find(int id) {
        for (Icon icon : ALL) {
            if (icon.id == id) {
                return icon;
            }
        }
        return null;
    }

    /** 该 ID 是否适合在左舵车仪表上显示。 */
    static boolean selectable(Icon icon) {
        return icon != null && icon.excluded == null;
    }

    /** 供界面显示的一行摘要：{@code "左转（ID 27，0x13/0x00）"}。 */
    static String describe(Icon icon) {
        if (icon == null) {
            return "未知图标";
        }
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "%s（ID %d，0x%02X/0x%02X）", icon.label, icon.id, icon.steer1, icon.steer2));
        if (icon.excluded != null) {
            text.append(" · 不显示：").append(icon.excluded);
        } else if (icon.experimental) {
            text.append(" · 实验性");
        }
        return text.toString();
    }
}
