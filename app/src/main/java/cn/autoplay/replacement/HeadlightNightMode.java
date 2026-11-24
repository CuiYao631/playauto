package cn.autoplay.replacement;

import android.os.Handler;
import android.util.Log;

import java.lang.reflect.Method;

final class HeadlightNightMode {
    private final Handler handler;
    private final Runnable changed;
    private Object manager;
    private Object listener;
    private Method removeCallback;
    private boolean attempted;
    private int generation;
    private int nightSetting = -1;

    HeadlightNightMode(Handler handler, Runnable changed) {
        this.handler = handler;
        this.changed = changed;
    }

    int currentSetting() {
        return nightSetting;
    }

    /**
     * 调试用：直接注入一个模拟的大灯状态，绕过 MCU 监听。
     *
     * <p>大灯联动依赖 {@code com.chinatsp.mcumanager.McuManager}（常安车机专用）。
     * 在没有该服务的环境（普通手机、模拟器）或用户只是想验证整条链路时，
     * 用这个入口伪造「大灯开/关」，后面的 {@link #changed} 回调与下发盒子的
     * 流程与真实大灯完全一致 —— 因此能验证夜间模式联动是否正常工作。
     *
     * <p>与 {@link #accept} 不同，这里不检查 {@code attempted}：真实 MCU 不可用时
     * 也必须能测试。
     *
     * @param setting {@code 1} 大灯开（夜间）、{@code 0} 大灯关（白天）
     * @return 是否接受该值；非法取值返回 {@code false}
     */
    boolean simulate(int setting) {
        if (setting != 0 && setting != 1) {
            return false;
        }
        if (nightSetting == setting) {
            // 状态没变也要回调一次，让用户重按能重新下发（排查下发失败很有用）
            if (changed != null) changed.run();
            return true;
        }
        nightSetting = setting;
        if (changed != null) changed.run();
        return true;
    }

    /** 真实 MCU 是否已接上（未接上时只能靠 {@link #simulate} 测试）。 */
    boolean mcuAvailable() {
        return manager != null;
    }

    static int settingForStatus(Object status, int previous) {
        if (!(status instanceof Enum<?>)) return previous;
        String name = ((Enum<?>) status).name();
        if ("LIGHT_OPEN".equals(name)) return 1;
        if ("LIGHT_CLOSE".equals(name)) return 0;
        return previous;
    }

    void start() {
        if (attempted) return;
        attempted = true;
        int epoch = ++generation;
        try {
            Class<?> managerType = Class.forName("com.chinatsp.mcumanager.McuManager");
            Class<?> listenerType = Class.forName("com.chinatsp.mcumanager.McuDataChangedListener");
            manager = managerType.getMethod("getInstance").invoke(null);
            if (manager == null) throw new IllegalStateException("McuManager unavailable");
            removeCallback = managerType.getMethod("removeMcuDataChangedCallback", listenerType);
            listener = new Callback(this, epoch);
            managerType.getMethod("addMcuDataChangedCallback", listenerType).invoke(manager, listener);
            accept(managerType.getMethod("getWidthLightStatus").invoke(manager), epoch);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            Log.w("AutoPlayHeadlights", "Headlight night mode unavailable", exception);
            releaseListener();
            generation++;
        }
    }

    private void accept(Object status, int epoch) {
        if (epoch != generation || !attempted) return;
        int next = settingForStatus(status, nightSetting);
        if (next == nightSetting) return;
        nightSetting = next;
        changed.run();
    }

    void stop() {
        generation++;
        releaseListener();
        attempted = false;
        nightSetting = -1;
    }

    private void releaseListener() {
        if (manager != null && listener != null && removeCallback != null) {
            try {
                removeCallback.invoke(manager, listener);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                Log.w("AutoPlayHeadlights", "Cannot remove headlight listener", exception);
            }
        }
        listener = null;
        manager = null;
        removeCallback = null;
    }

    private static final class Callback extends com.chinatsp.mcumanager.McuDataChangedListener {
        private final HeadlightNightMode owner;
        private final int epoch;

        Callback(HeadlightNightMode owner, int epoch) {
            this.owner = owner;
            this.epoch = epoch;
        }

        @Override public void onWidthLightStatusChanged(com.chinatsp.mcumanager.Config.WidthLightStatus status) {
            owner.handler.post(() -> owner.accept(status, epoch));
        }
    }
}