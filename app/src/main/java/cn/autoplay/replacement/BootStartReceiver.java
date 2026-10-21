package cn.autoplay.replacement;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

public final class BootStartReceiver extends BroadcastReceiver {
    static final String PREFERENCE = "boot_auto_start";
    static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL = "boot_start";
    private static final String TAG = "AutoPlayBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .getBoolean(PREFERENCE, false)) {
            Log.i(TAG, "Boot auto-start disabled");
            return;
        }
        Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(context)) {
            Log.i(TAG, "Background activity launch restricted; posting launch notification");
            notifyLaunch(context, launch);
            return;
        }
        try {
            context.startActivity(launch);
            Log.i(TAG, "Requested application launch after boot");
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.e(TAG, "Unable to launch application after boot", exception);
            notifyLaunch(context, launch);
        }
    }

    private static void notifyLaunch(Context context, Intent launch) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            Log.e(TAG, "Notification service unavailable; boot launch notification cannot be shown");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "开机启动", NotificationManager.IMPORTANCE_DEFAULT));
        }
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Notification permission denied; open AutoPlay manually");
            return;
        }
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled; open AutoPlay manually");
            return;
        }
        PendingIntent pending = PendingIntent.getActivity(context, NOTIFICATION_ID, launch,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        manager.notify(NOTIFICATION_ID, builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("PlayAuto 开机启动")
                .setContentText("系统限制后台打开界面，点击启动 PlayAuto")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build());
    }
}
