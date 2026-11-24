package cn.autoplay.replacement;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 读取车机 MCU 的大灯状态，用于「夜间模式 - 大灯联动」。
 *
 * <p>MCU 接口（{@code com.chinatsp.mcumanager.*}）只在车机上存在，项目里的
 * {@code app/src/mcuApi} 是从车机反推的 <b>compileOnly 桩</b>，类名、方法名、
 * 枚举常量名都可能是推测的。因此这里避免任何硬假设：
 *
 * <ul>
 *   <li><b>不依赖回调</b>：轮询 {@code getWidthLightStatus()} 作为主要数据源。
 *       回调的接口/类形态、方法名、参数类型只要有一处与推断不符，注册不会报错，
 *       却永远收不到通知 —— 只靠回调会「看起来绑定成功，实际拿不到状态」。</li>
 *   <li><b>方法名多个候选</b>：逐个尝试 {@code getWidthLightStatus} 等名字。</li>
 *   <li><b>枚举名按词元匹配</b>：{@code LIGHT_OPEN}、{@code WIDTH_LIGHT_ON}、
 *       {@code OPEN} 都能识别；{@code INVALID}/{@code UNKNOWN} 保持原状态。</li>
 *   <li><b>绑定失败会重试</b>：车机开机时 MCU 服务可能未就绪，首次失败不代表永久不可用。</li>
 * </ul>
 *
 * <p>{@link #probe()} 输出反射探测报告，用于在真机上确认实际的类与方法结构。
 */
final class HeadlightNightMode {
    private static final String TAG = "AutoPlayHeadlights";

    /** 轮询间隔。MCU 状态读取是一次本地调用，2 秒一次开销可忽略。 */
    private static final long POLL_INTERVAL_MS = 2000L;
    /** 绑不上 MCU（服务未就绪）时的重试间隔。 */
    private static final long REBIND_INTERVAL_MS = 15_000L;
    private static final String MCU_MANAGER = "com.chinatsp.mcumanager.McuManager";
    private static final String MCU_LISTENER = "com.chinatsp.mcumanager.McuDataChangedListener";

    private static final String[] GET_STATUS_NAMES = {
            "getWidthLightStatus", "getLightStatus", "getWideLightStatus",
            "getPositionLightStatus", "getIlluminanceStatus",
    };
    private static final String[] ADD_CALLBACK_NAMES = {
            "addMcuDataChangedCallback", "registerMcuDataChangedCallback",
            "addDataChangedCallback", "registerDataChangedCallback",
            "addListener", "registerListener",
    };
    private static final String[] REMOVE_CALLBACK_NAMES = {
            "removeMcuDataChangedCallback", "unregisterMcuDataChangedCallback",
            "removeDataChangedCallback", "unregisterDataChangedCallback",
            "removeListener", "unregisterListener",
    };

    private final Context context;
    private final Handler handler;
    private final Runnable changed;
    private Object manager;
    private Object listener;
    private Method removeCallback;
    private Method getStatus;
    private boolean attempted;
    private int generation;
    private int nightSetting = -1;
    private long lastBindAt;
    /** 最近一次读到的原始状态描述，供探测报告与调试窗口显示。 */
    private String lastRawStatus = "未读取";
    /** 回调是否至少触发过一次（用于区分「回调不来」与「状态没变」）。 */
    private boolean callbackSeen;
    /** 最近一次绑定失败的原因，供探测报告显示。 */
    private String bindError = "";
    private final Runnable pollTask = this::tick;

    HeadlightNightMode(Context context, Handler handler, Runnable changed) {
        this.context = context;
        this.handler = handler;
        this.changed = changed;
    }

    int currentSetting() {
        return nightSetting;
    }

    /** 最近一次读到的原始状态（类名 + 常量名），用于排查。 */
    String lastRawStatus() {
        return lastRawStatus;
    }

    /** 回调是否成功触发过。为 {@code false} 说明监听注册无效，只能靠轮询。 */
    boolean callbackSeen() {
        return callbackSeen;
    }

    /** 最近一次绑定失败的原因；空串表示没有失败过。 */
    String bindError() {
        return bindError;
    }

    /**
     * 调试用：直接注入一个模拟的大灯状态，绕过 MCU 监听。
     *
     * <p>大灯联动依赖车机的 {@code McuManager}。在没有该服务的环境（普通手机、
     * 模拟器）或只是想验证整条链路时，用这个入口伪造「大灯开/关」，
     * 后面的 {@link #changed} 回调与下发盒子的流程与真实大灯完全一致。
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

    /**
     * 把 MCU 状态对象解释成夜间模式取值。
     *
     * <p>只在真正认出「开」或「关」时才改变状态；无法识别（{@code null}、
     * {@code INVALID}/{@code UNKNOWN}、非枚举/整数/字符串）一律保留原值，
     * 避免一次异常值把已有的夜间模式翻掉。
     *
     * @param previous 当前已知取值，{@code -1} 表示尚未确定
     */
    static int settingForStatus(Object status, int previous) {
        if (status == null) {
            return previous;
        }
        if (status instanceof Number) {
            // 有些固件的 getter 直接返回整数编码，0 视为关、非 0 视为开
            return ((Number) status).intValue() == 0 ? 0 : 1;
        }
        if (status instanceof Boolean) {
            return ((Boolean) status) ? 1 : 0;
        }
        String name = status instanceof Enum<?> ? ((Enum<?>) status).name() : status.toString();
        if (name == null || name.isEmpty()) {
            return previous;
        }
        Boolean parsed = parseLightName(name);
        return parsed == null ? previous : (parsed ? 1 : 0);
    }

    /**
     * 按词元解析状态名，避免依赖具体常量拼写。
     *
     * @return {@code TRUE} 开、{@code FALSE} 关、{@code null} 无法识别
     */
    private static Boolean parseLightName(String raw) {
        String[] tokens = raw.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+");
        boolean open = false;
        boolean close = false;
        for (String token : tokens) {
            switch (token) {
                case "INVALID":
                case "UNKNOWN":
                case "NONE":
                case "NULL":
                case "ERROR":
                case "FAIL":
                    return null;
                case "OPEN":
                case "ON":
                case "ENABLE":
                case "ENABLED":
                case "HIGH":
                case "TRUE":
                case "NIGHT":
                case "DARK":
                    open = true;
                    break;
                case "CLOSE":
                case "CLOSED":
                case "OFF":
                case "DISABLE":
                case "DISABLED":
                case "LOW":
                case "FALSE":
                case "DAY":
                case "BRIGHT":
                    close = true;
                    break;
                default:
                    break;
            }
        }
        if (open && !close) {
            return Boolean.TRUE;
        }
        if (close && !open) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 开始监听大灯状态。首次失败会自动重试（车机开机时 MCU 服务可能未就绪）。 */
    void start() {
        if (attempted) {
            return;
        }
        attempted = true;
        generation++;
        bind(generation);
        schedulePoll();
    }

    void stop() {
        generation++;
        attempted = false;
        callbackSeen = false;
        lastRawStatus = "未读取";
        bindError = "";
        if (handler != null) {
            handler.removeCallbacks(pollTask);
        }
        releaseListener();
        nightSetting = -1;
    }

    // ---- 轮询（主要数据源） ----

    private void schedulePoll() {
        if (handler == null || !attempted) {
            return;
        }
        handler.removeCallbacks(pollTask);
        handler.postDelayed(pollTask, POLL_INTERVAL_MS);
    }

    private void tick() {
        if (!attempted) {
            return;
        }
        int epoch = generation;
        if (manager == null) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastBindAt >= REBIND_INTERVAL_MS) {
                Log.i(TAG, "MCU 尚未就绪，重试绑定");
                bind(epoch);
            }
        } else {
            accept(readStatus(epoch), epoch);
        }
        schedulePoll();
    }

    /** 读取一次状态；任何异常都只记日志，返回 {@code null} 表示这次没读到。 */
    private Object readStatus(int epoch) {
        if (manager == null || epoch != generation) {
            return null;
        }
        try {
            if (getStatus == null) {
                getStatus = findMethod(manager.getClass(), GET_STATUS_NAMES, 0);
            }
            if (getStatus == null) {
                return null;
            }
            return getStatus.invoke(manager);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Log.w(TAG, "读取大灯状态失败：" + exception);
            return null;
        }
    }

    private void accept(Object status, int epoch) {
        if (epoch != generation || !attempted) {
            return;
        }
        if (status != null) {
            lastRawStatus = status.getClass().getName() + "：" + describe(status);
        }
        int next = settingForStatus(status, nightSetting);
        if (next == nightSetting) {
            return;
        }
        Log.i(TAG, "大灯状态变化 → " + (next == 1 ? "开（夜间）" : "关（白天）")
                + "，原始值=" + lastRawStatus);
        nightSetting = next;
        if (changed != null) {
            changed.run();
        }
    }

    // ---- 绑定 ----

    private void bind(int epoch) {
        lastBindAt = SystemClock.elapsedRealtime();
        if (epoch != generation) {
            return;
        }
        try {
            Class<?> managerType = Class.forName(MCU_MANAGER);
            Object instance = obtainManager(managerType);
            if (instance == null) {
                throw new IllegalStateException("McuManager.getInstance() 返回 null");
            }
            manager = instance;
            getStatus = findMethod(managerType, GET_STATUS_NAMES, 0);
            registerCallback(managerType, epoch);
            bindError = "";
            Log.i(TAG, "MCU 已绑定：manager=" + managerType.getName()
                    + "，状态方法=" + (getStatus == null ? "未找到" : getStatus.getName())
                    + "，回调=" + (listener == null ? "未注册" : "已注册"));
            accept(readStatus(epoch), epoch);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            bindError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            Log.w(TAG, "MCU 绑定失败（" + bindError + "），将只依赖轮询重试", exception);
            releaseListener();
        }
    }

    private Object obtainManager(Class<?> managerType) throws ReflectiveOperationException {
        Method noArg = findMethod(managerType, new String[]{"getInstance"}, 0);
        if (noArg != null) {
            return noArg.invoke(null);
        }
        Method withContext = findMethod(managerType, new String[]{"getInstance"}, 1);
        if (withContext != null && context != null) {
            return withContext.invoke(null, context);
        }
        Method getter = findMethod(managerType, new String[]{"getMcuManager"}, 0);
        return getter == null ? null : getter.invoke(null);
    }

    /**
     * 注册状态回调。回调只是「更及时」的优化，失败不影响轮询。
     *
     * <p>监听类型是接口时用 {@link Proxy} 动态代理（不关心方法名，只看参数个数与
     * 名字里是否含 light），是类时才用 {@link Callback} 子类。
     */
    private void registerCallback(Class<?> managerType, int epoch) {
        Method add = findMethod(managerType, ADD_CALLBACK_NAMES, 1);
        if (add == null) {
            Log.i(TAG, "MCU 没有可用的注册回调方法，只使用轮询");
            return;
        }
        Class<?> listenerType = add.getParameterTypes()[0];
        try {
            listener = listenerType.isInterface()
                    ? createProxy(listenerType, epoch)
                    : new Callback(this, epoch);
            add.invoke(manager, listener);
            removeCallback = findMethod(managerType, REMOVE_CALLBACK_NAMES, 1);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            Log.w(TAG, "注册大灯回调失败，改用轮询：" + exception);
            listener = null;
            removeCallback = null;
        }
    }

    private Object createProxy(Class<?> listenerType, int epoch) {
        InvocationHandler invocation = (proxy, method, args) -> {
            if (args != null && args.length == 1
                    && method.getName().toLowerCase(Locale.ROOT).contains("light")) {
                Object status = args[0];
                callbackSeen = true;
                if (handler != null) {
                    handler.post(() -> accept(status, epoch));
                } else {
                    accept(status, epoch);
                }
            }
            return null;
        };
        return Proxy.newProxyInstance(listenerType.getClassLoader(),
                new Class<?>[]{listenerType}, invocation);
    }

    private void releaseListener() {
        if (manager != null && listener != null && removeCallback != null) {
            try {
                removeCallback.invoke(manager, listener);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                Log.w(TAG, "移除大灯监听失败", exception);
            }
        }
        listener = null;
        removeCallback = null;
        manager = null;
    }

    private static Method findMethod(Class<?> type, String[] names, int paramCount) {
        Method[] methods = safeMethods(type);
        for (String name : names) {
            for (Method method : methods) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                if (method.getParameterTypes().length == paramCount
                        && Modifier.isPublic(method.getModifiers())) {
                    return method;
                }
            }
        }
        return null;
    }

    private static String describe(Object status) {
        return status instanceof Enum<?> ? ((Enum<?>) status).name() : String.valueOf(status);
    }

    // ---- 探测报告 ----

    /**
     * 反射探测车机 MCU 接口的真实结构，供设置页「MCU 大灯探测」显示。
     *
     * <p>输出同时写 logcat，便于在车机上用 {@code adb logcat -s AutoPlayHeadlights} 取回。
     */
    String probe() {
        String report = buildProbeReport();
        // logcat 单条上限约 4 KB，按 2000 字符切片，避免后半段被截断
        int chunk = 2000;
        for (int start = 0; start < report.length(); start += chunk) {
            Log.i(TAG, report.substring(start, Math.min(start + chunk, report.length())));
        }
        return report;
    }

    /** 纯反射组装探测报告，不写日志（便于在纯 JVM 下测试）。 */
    String buildProbeReport() {
        List<String> lines = new ArrayList<>();
        lines.add("== AutoPlay MCU 探测 ==");
        lines.add("night_mode=" + (context == null ? "?"
                : context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                        .getInt("night_mode", 2)));
        lines.add("当前取值=" + nightSetting
                + "，原始状态=" + lastRawStatus
                + "，回调触发过=" + callbackSeen
                + "，已绑定=" + (manager != null));
        if (!bindError.isEmpty()) {
            lines.add("最近绑定失败：" + bindError);
        }

        Class<?> managerType = load(MCU_MANAGER, lines);
        if (managerType == null) {
            lines.add("");
            lines.add("没有 McuManager：本机不是常安车机（或该服务未安装）。");
            lines.add("这不是 bug —— 普通手机上大灯联动不可用，只能用「大灯模拟悬浮窗」测试。");
            return finish(lines);
        }

        lines.add("");
        lines.add("McuManager=" + managerType.getName()
                + "（interface=" + managerType.isInterface() + "）");
        lines.add("-- 与 light / 回调相关的方法 --");
        for (Method method : safeMethods(managerType)) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("light") && !name.contains("callback") && !name.contains("listener")) {
                continue;
            }
            lines.add("  " + describeMethod(method));
        }

        Method statusMethod = findMethod(managerType, GET_STATUS_NAMES, 0);
        lines.add("");
        lines.add("状态读取方法：" + (statusMethod == null
                ? "未找到候选名 " + String.join("/", GET_STATUS_NAMES)
                : describeMethod(statusMethod)));
        if (statusMethod != null) {
            Class<?> returnType = statusMethod.getReturnType();
            lines.add("返回类型：" + returnType.getName());
            Object[] constants = returnType.isEnum() ? returnType.getEnumConstants() : null;
            if (constants != null) {
                StringBuilder builder = new StringBuilder("枚举常量：");
                for (Object constant : constants) {
                    builder.append(constant instanceof Enum<?>
                            ? ((Enum<?>) constant).name() : String.valueOf(constant)).append(' ');
                }
                lines.add(builder.toString().trim());
            }
        }

        Class<?> listenerType = load(MCU_LISTENER, lines);
        if (listenerType != null) {
            lines.add("");
            lines.add("McuDataChangedListener=" + listenerType.getName()
                    + "（interface=" + listenerType.isInterface() + "）");
            for (Method method : safeMethods(listenerType)) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                lines.add("  " + describeMethod(method));
            }
        }

        lines.add("");
        Method add = findMethod(managerType, ADD_CALLBACK_NAMES, 1);
        lines.add("注册回调方法：" + (add == null ? "未找到"
                : describeMethod(add) + "，监听参数=" + add.getParameterTypes()[0].getName()));

        Object status = manager != null ? readStatus(generation) : null;
        lines.add("");
        lines.add("本次实测读取=" + (status == null ? "null" : describe(status))
                + "，解析结果=" + settingForStatus(status, -1));
        return finish(lines);
    }

    private Class<?> load(String name, List<String> lines) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError exception) {
            lines.add("找不到 " + name + "：" + exception.getClass().getSimpleName());
            return null;
        }
    }

    private static Method[] safeMethods(Class<?> type) {
        try {
            return type.getMethods();
        } catch (RuntimeException | LinkageError exception) {
            return new Method[0];
        }
    }

    private static String describeMethod(Method method) {
        StringBuilder builder = new StringBuilder(method.getReturnType().getSimpleName())
                .append(' ').append(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int index = 0; index < parameters.length; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(parameters[index].getSimpleName());
        }
        return builder.append(')').toString();
    }

    private String finish(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    /**
     * 大灯回调监听器（仅当监听类型是<b>类</b>时使用）。
     *
     * <p>监听类型是接口时走 {@link Proxy}，不会加载本类 —— 因此这里可以安全继承
     * 桩代码里的类，即使真机上的同名类型是接口也不会触发链接错误。
     */
    private static final class Callback extends com.chinatsp.mcumanager.McuDataChangedListener {
        private final HeadlightNightMode owner;
        private final int epoch;

        Callback(HeadlightNightMode owner, int epoch) {
            this.owner = owner;
            this.epoch = epoch;
        }

        @Override
        public void onWidthLightStatusChanged(
                com.chinatsp.mcumanager.Config.WidthLightStatus status) {
            owner.callbackSeen = true;
            if (owner.handler != null) {
                owner.handler.post(() -> owner.accept(status, epoch));
            } else {
                owner.accept(status, epoch);
            }
        }
    }
}
