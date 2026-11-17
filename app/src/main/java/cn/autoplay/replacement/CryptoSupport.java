package cn.autoplay.replacement;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 探测原生加密库 {@code libnx1.so} 在当前构建下能否安全使用。
 *
 * <p><b>背景</b>：该库首次调用 {@code stCheck} 时会通过隐藏 API
 * （{@code ActivityThread.currentActivityThread()} → {@code getApplication()}）拿到应用上下文，
 * 随后解析 {@code android/content/pm/Signature} 校验 APK 签名。本应用是重新构建并用自定义
 * keystore 签名的，校验没有通过，库内部会执行一次空指针写入
 * （tombstone：{@code SIGSEGV @ libnx1.so stCheck+548 → 0xa84}，fault addr 0x0）
 * 直接把进程打死，所以「在进程里试一下」是不可行的。
 *
 * <p><b>做法</b>：把试探交给独立进程 {@link CryptoProbeService}（{@code :crypto-probe}）。
 * 探针进程被杀只会影响它自己：没有回传结果就判定为不可用，主进程改为未加密握手，
 * 从而不再闪退。结论按安装版本缓存，升级后会重新探测。
 */
final class CryptoSupport {

    private static final String TAG = "CryptoSupport";
    private static final String PREFS = "crypto_probe";
    private static final String KEY_VERSION = "probed_version";
    private static final String KEY_USABLE = "native_usable";
    private static final long PROBE_TIMEOUT_MS = 4000L;

    private static final CountDownLatch RESOLVED = new CountDownLatch(1);

    private static volatile boolean usable;
    private static volatile boolean resolved;
    private static volatile boolean probeStarted;
    private static volatile Context appContext;

    private CryptoSupport() {
    }

    /** 在连接服务启动时调用：读取缓存结论，必要时异步启动探针。 */
    static void install(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        appContext = app;
        if (resolved) return;

        String version = currentVersion(app);
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (version.equals(prefs.getString(KEY_VERSION, ""))) {
            boolean cached = prefs.getBoolean(KEY_USABLE, false);
            resolve(cached, version, app, true);
            Log.i(TAG, "沿用缓存的加密库结论：" + cached + "（版本 " + version + "）");
            return;
        }
        startProbe(app, version);
    }

    static boolean isResolved() {
        return resolved;
    }

    static boolean isUsable() {
        return resolved && usable;
    }

    /** 等待探测结论，供盒子线程在需要加密前同步确认。 */
    static boolean awaitUsable(long timeoutMs) {
        if (!resolved) {
            try {
                RESOLVED.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        return isUsable();
    }

    private static void startProbe(Context app, String version) {
        if (probeStarted) return;
        probeStarted = true;

        Handler main = new Handler(Looper.getMainLooper());
        ResultReceiver receiver = new ResultReceiver(main) {
            @Override protected void onReceiveResult(int resultCode, Bundle resultData) {
                resolve(resultCode == CryptoProbeService.RESULT_USABLE, version, app, false);
            }
        };

        Intent intent = new Intent(app, CryptoProbeService.class);
        intent.putExtra(CryptoProbeService.EXTRA_RECEIVER, receiver);
        try {
            app.startService(intent);
        } catch (RuntimeException exception) {
            Log.w(TAG, "探针服务启动失败", exception);
            resolve(false, version, app, false);
            return;
        }

        // 探针进程被原生库打死时不会有任何回调，超时即视为不可用
        main.postDelayed(() -> {
            if (!resolved) {
                Log.w(TAG, "加密库探针无响应（进程可能已被 native 库终止），判定为不可用");
            }
            resolve(false, version, app, false);
        }, PROBE_TIMEOUT_MS);
    }

    private static void resolve(boolean usableResult, String version, Context app, boolean cached) {
        synchronized (CryptoSupport.class) {
            if (resolved) return;
            usable = usableResult;
            resolved = true;
            if (!cached) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_USABLE, usableResult)
                        .putString(KEY_VERSION, version)
                        .apply();
            }
            RESOLVED.countDown();
        }
        Log.i(TAG, "原生加密库可用：" + usableResult + (cached ? "（缓存）" : "（本次探测）"));
    }

    private static String currentVersion(Context app) {
        try {
            String version = app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0).versionName;
            return version == null ? "unknown" : version;
        } catch (Exception exception) {
            return "unknown";
        }
    }

    /** 供设置页展示当前加密能力状态。 */
    static String describe() {
        if (!resolved) return "检测中";
        if (usable) return "可用";
        return "不可用（应用签名与原版不一致，将回退为未加密连接）";
    }
}
