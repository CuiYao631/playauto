package cn.autoplay.replacement;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/**
 * 原车组件探测：仪表投射、车机 MCU（大灯联动）、原车语音助理。
 *
 * <p>环境检测页与仪表调试页共用这里的包名清单，避免两处各写一份而漂移。
 *
 * <p><b>只能判断安装/启用状态，判断不了服务是否在运行。</b>原车接收器是动态注册的，
 * 「已安装并启用」不等于「正在接收广播」，所以这里不能作为「功能一定能用」的结论。
 *
 * <p>分类与文案都是纯函数，可在 {@code ProtocolFrameSelfTest} 里直接跑；
 * 只有 {@link #presence} 需要 {@link Context}。
 */
final class VehicleComponents {
    /**
     * 仪表投射相关组件。
     *
     * <p>按用途归类，与 MCU 分开；包名本身来自原车反编译结果，未逐一验证
     * 每个包具体承载哪个服务。
     */
    static final String[] CLUSTER_PACKAGES = {
            "com.chinatsp.dual.service",
            "com.chinatsp.tsetservices",
    };

    /** 车机 MCU 服务进程包。 */
    static final String MCU_SERVICE_PACKAGE = "com.chinatsp.mcuservice2";

    /** 大灯联动实际调用的 MCU 管理接口类。 */
    static final String MCU_MANAGER_CLASS = "com.chinatsp.mcumanager.McuManager";

    /** 原车语音按键广播接收器。 */
    static final String VOICE_KEY_PACKAGE = VehicleVoiceControl.KEY_PACKAGE;

    /** 原车语音助理（含唤醒引擎）。 */
    static final String VOICE_ASSISTANT_PACKAGE = VehicleVoiceControl.ASSISTANT_PACKAGE;

    /** 组件存在性三态。 */
    enum Presence {
        /** 已安装并启用。 */
        PRESENT,
        /** 已安装但被禁用。 */
        DISABLED,
        /** 未安装。 */
        MISSING,
    }

    private VehicleComponents() {
    }

    /**
     * 由 PackageManager 的两项事实归到三态。
     *
     * @param installed 是否存在该包（含被禁用的）
     * @param enabled   是否启用
     */
    static Presence classify(boolean installed, boolean enabled) {
        if (!installed) {
            return Presence.MISSING;
        }
        return enabled ? Presence.PRESENT : Presence.DISABLED;
    }

    /** 三态的中文说明。 */
    static String describe(Presence presence) {
        switch (presence) {
            case PRESENT:
                return "已安装并启用";
            case DISABLED:
                return "已安装但被禁用";
            default:
                return "未安装";
        }
    }

    /**
     * 查询单个包的安装/启用状态。
     *
     * <p>用 {@link PackageManager#GET_DISABLED_COMPONENTS} 之类的高位掩码拿不到
     * 「应用是否被禁用」，但 {@code getApplicationInfo} 返回的 {@link ApplicationInfo#enabled}
     * 字段可靠；被禁用的应用也能查到，所以这里不会把「被禁用」误报成「未安装」。
     */
    static Presence presence(Context context, String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(packageName, PackageManager.GET_DISABLED_COMPONENTS);
            return classify(true, info.enabled);
        } catch (PackageManager.NameNotFoundException exception) {
            return Presence.MISSING;
        }
    }

    /**
     * 仪表组件检测。
     *
     * @return {@code ok} 表示全部已安装并启用
     */
    static SettingsScreens.CheckResult cluster(String[] packages, Presence[] states) {
        boolean ok = true;
        StringBuilder missing = new StringBuilder();
        for (int index = 0; index < packages.length; index++) {
            if (states[index] != Presence.PRESENT) {
                ok = false;
                if (missing.length() > 0) {
                    missing.append('、');
                }
                missing.append(packages[index]);
            }
        }
        if (ok) {
            return new SettingsScreens.CheckResult(true, "可用");
        }
        return new SettingsScreens.CheckResult(false, "不可用：" + missing);
    }

    /**
     * MCU（大灯联动）检测。
     *
     * <p>{@code ok} 要求「接口类存在」且「已连上」：只有连上了才能真正读到灯光状态，
     * 而 {@code bound=false} 时大灯联动是静默失效的。
     *
     * @param classPresent   反射能否找到 {@link #MCU_MANAGER_CLASS}
     * @param servicePackage 车机 MCU 服务包的状态；未安装时绑定必然失败，优先报这个原因
     * @param bound          本次会话是否已绑上 MCU
     * @param bindError      最近一次绑定失败原因，无失败时为空串
     * @param rawStatus      最近一次读到的灯光原始值
     */
    static SettingsScreens.CheckResult mcu(boolean classPresent, Presence servicePackage,
            boolean bound, String bindError, String rawStatus) {
        if (!classPresent) {
            return new SettingsScreens.CheckResult(false, "不可用：本机没有 McuManager");
        }
        if (bound) {
            return new SettingsScreens.CheckResult(true, "可用（灯光：" + rawStatus + "）");
        }
        if (servicePackage == Presence.MISSING) {
            return new SettingsScreens.CheckResult(false, "不可用：MCU 服务包未安装");
        }
        return new SettingsScreens.CheckResult(false, bindError.isEmpty()
                ? "不可用：尚未连上车机 MCU"
                : "不可用：" + bindError);
    }

    /**
     * 原车语音检测。
     *
     * <p>这里的判据是<b>功能意图</b>而非「装没装」：目标状态是原车语音不再抢按键与唤醒词，
     * 所以「被禁用」算通过，「仍启用」才算问题。
     */
    static SettingsScreens.CheckResult voice(Presence key, Presence assistant) {
        StringBuilder active = new StringBuilder();
        if (key == Presence.PRESENT) {
            active.append(VOICE_KEY_PACKAGE);
        }
        if (assistant == Presence.PRESENT) {
            if (active.length() > 0) {
                active.append('、');
            }
            active.append(VOICE_ASSISTANT_PACKAGE);
        }
        if (active.length() == 0) {
            return new SettingsScreens.CheckResult(true, "不存在");
        }
        return new SettingsScreens.CheckResult(false, "存在语音助手：" + active);
    }
}
