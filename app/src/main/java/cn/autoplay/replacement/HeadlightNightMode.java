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