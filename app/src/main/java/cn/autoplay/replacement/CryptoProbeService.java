package cn.autoplay.replacement;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;

import cn.manstep.phonemirrorBox.BoxInterface.BoxCore;

/**
 * 在独立进程 {@code :crypto-probe} 里试探 {@code libnx1} 是否可用。
 *
 * <p>该库首次调用 {@code stCheck} 时可能直接写入空指针杀死整个进程
 * （见 {@link CryptoSupport} 的说明）。把它放在独立进程里试探，
 * 崩掉的只是探针进程，主进程据此得到「可用 / 不可用」的结论。
 */
public final class CryptoProbeService extends Service {

    private static final String TAG = "CryptoProbeService";

    static final String EXTRA_RECEIVER = "cn.autoplay.replacement.probe.receiver";
    static final int RESULT_USABLE = 1;
    static final int RESULT_UNUSABLE = 0;

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ResultReceiver receiver = null;
        if (intent != null) {
            try {
                receiver = intent.getParcelableExtra(EXTRA_RECEIVER);
            } catch (RuntimeException ignored) {
                receiver = null;
            }
        }

        boolean usable;
        try {
            usable = BoxCore.isAvailable() && probe();
        } catch (Throwable error) {
            Log.w(TAG, "原生加密探针异常：" + error.getClass().getSimpleName());
            usable = false;
        }

        if (receiver != null) {
            try {
                receiver.send(usable ? RESULT_USABLE : RESULT_UNUSABLE, null);
            } catch (RuntimeException ignored) {
                // 主进程可能已经回收，忽略
            }
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    /** 与真实使用方式一致：同一数组既作输入也作输出，加密一次 28 字节负载。 */
    private static boolean probe() {
        byte[] payload = new byte[BoxOpenRequest.PAYLOAD_SIZE];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) index;
        }
        return BoxCore.stCheck(true, payload, 1, payload);
    }
}
