package cn.autoplay.replacement;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.widget.ProgressBar;

final class AndroidCompat {
    static final String AVC_MIME_TYPE = "video/avc";

    private AndroidCompat() { }

    static boolean hasMicrophone(Context context) {
        return context.checkCallingOrSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    static void registerReceiver(Context context, android.content.BroadcastReceiver receiver,
            android.content.IntentFilter filter, boolean exported) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter,
                    exported ? Context.RECEIVER_EXPORTED : Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerLegacyReceiver(context, receiver, filter);
        }
    }

    // Receiver export flags do not exist before API 33.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void registerLegacyReceiver(Context context, android.content.BroadcastReceiver receiver,
            android.content.IntentFilter filter) {
        context.registerReceiver(receiver, filter);
    }

    static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT >= 23
                ? android.provider.Settings.canDrawOverlays(context)
                : context.checkCallingOrSelfPermission(Manifest.permission.SYSTEM_ALERT_WINDOW)
                        == PackageManager.PERMISSION_GRANTED;
    }

    static MediaCodecInfo[] decoders() {
        if (Build.VERSION.SDK_INT >= 21) {
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        }
        MediaCodecInfo[] codecs = new MediaCodecInfo[MediaCodecList.getCodecCount()];
        for (int i = 0; i < codecs.length; i++) codecs[i] = MediaCodecList.getCodecInfoAt(i);
        return codecs;
    }

    static void tintSpinner(ProgressBar spinner) {
        if (Build.VERSION.SDK_INT >= 21) {
            spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        } else {
            spinner.getIndeterminateDrawable().mutate().setColorFilter(
                    Ui.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    static int[] videoSize(MediaCodecInfo info, String type) {
        return Build.VERSION.SDK_INT >= 21 ? Api21.videoSize(info, type) : null;
    }

    @android.annotation.TargetApi(21)
    private static final class Api21 {
        static int[] videoSize(MediaCodecInfo info, String type) {
            MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(type).getVideoCapabilities();
            return video == null ? null : new int[]{
                    video.getSupportedWidths().getUpper(), video.getSupportedHeights().getUpper()};
        }
    }
}
