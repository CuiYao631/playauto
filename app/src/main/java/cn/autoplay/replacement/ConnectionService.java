package cn.autoplay.replacement;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;

import cn.manstep.phonemirrorBox.BoxInterface.BoxCore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Owns the USB session and audio independently of the projection Activity. */
public final class ConnectionService extends Service {
    static final String PREFERENCE = "background_connection";
    private static final String TAG = "AutoPlayConnection";
    private static final String USB_PERMISSION = "cn.autoplay.replacement.USB_PERMISSION";
    private static final String MANAGE_USB = "android.permission.MANAGE_USB";
    private static final String CHANNEL = "connection";
    private static final int NOTIFICATION_ID = 1002;

    interface Client {
        default void onNavigationLogChanged() { }
        default void onHostUiRequested() { }
        default void onBoxCommandLogChanged() { }
        void onConnectionState();
        void onVideoFrame(VideoFrame frame);
        void onMicrophonePermissionNeeded();
    }

    final class LocalBinder extends Binder {
        ConnectionService getService() { return ConnectionService.this; }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HeadlightNightMode headlightNightMode =
            new HeadlightNightMode(this, handler, this::onHeadlightChanged);
    private final LocalBinder binder = new LocalBinder();
    private UsbManager usbManager;
    private UsbTransport connection;
    private volatile BoxProtocolSession session;
    private AudioPlayback playback;
    private AudioCapture capture;
    private PowerManager.WakeLock wakeLock;
    private MediaControls mediaSession;
    private Client client;
    /** 用悬浮窗模拟的副屏，承载 CMD_DASHBOARD_FRAME(44)。 */
    private SecondaryScreenOverlay secondaryScreen;
    /** 盒子指令日志悬浮窗（调试用），与副屏独立开关。 */
    private BoxLogOverlay boxLogOverlay;
    /** 大灯模拟悬浮窗（调试用），用于在没有真实 MCU 的环境测试夜间模式联动。 */
    private HeadlightTestOverlay headlightTestOverlay;
    private volatile boolean clientVisible;
    private boolean foreground;
    private boolean microphoneForeground;
    private boolean started;
    private boolean destroyed;
    private boolean rootUsbPending;
    private String deviceName;
    private String permissionDeviceName;
    private int width = 1920;
    private int height = 1080;
    private int microphoneDecodeType = -1;
    private int capturingDecodeType = -1;
    private volatile int generation;
    String boxStatus = "盒子：未连接";
    String phoneStatus = "手机：未连接";
    String boxVersion;
    /** 盒子固件检查所需的身份信息，来自 CMD_BOX_INFO(25)。 */
    String boxUuid;
    String boxManufacturer;
    String boxProductType;
    String boxFirmwareName;
    /** 盒子固件升级进度，-1 表示尚未开始。 */
    volatile int firmwareProgress = -1;
    /** 盒子固件升级状态，0 表示空闲；1 开始、2 成功、3 失败、5/6/7 OTA。 */
    volatile int firmwareStatus = 0;
    String boxBluetoothName;
    String boxWifiName;
    boolean phonePlugged;
    boolean bluetoothLinked;
    boolean wifiLinked;
    private String usbPermissionDiagnostic = "尚未申请 USB 授权";
    private String legacyUsbPermissionDiagnostic = "尚未尝试旧系统授权";
    private String rootUsbPermissionDiagnostic = "Root USB：尚未手动申请";

    private SharedPreferences prefs() {
        return getSharedPreferences("MainActivity", MODE_PRIVATE);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                    : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (USB_PERMISSION.equals(intent.getAction())) {
                usbPermissionDiagnostic = "收到授权回调："
                        + (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ? "允许" : "拒绝")
                        + (device == null ? "；未返回设备" : "");
                Log.i(TAG, usbPermissionDiagnostic);
                permissionDeviceName = null;
                if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        && usbManager.hasPermission(device)) {
                    openDevice(device);
                } else {
                    setBoxStatus("盒子：USB 授权未通过");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (device != null && device.getDeviceName().equals(permissionDeviceName)) {
                    permissionDeviceName = null;
                    usbPermissionDiagnostic = "等待授权的设备已拔出，可以重新申请";
                    setBoxStatus("盒子：等待授权的 USB 设备已拔出");
                }
                if (device != null && device.getDeviceName().equals(deviceName)) {
                    closeConnection();
                    setBoxStatus("盒子：车机盒已断开");
                }
            } else {
                connectAttached();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (prefs().contains("usb_transport")) {
            prefs().edit().remove("usb_transport").apply();
            Log.i(TAG, "Removed legacy USB transport selection; using Android USB");
        }
        VideoPreferences.migrate(prefs());
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        playback = createPlayback();
        capture = new AudioCapture(error -> handler.post(() -> {
            capturingDecodeType = -1;
            Log.e(TAG, "麦克风采集失败：" + error);
            setPhoneStatus("手机：麦克风采集失败：" + error);
        }));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoPlay:connection");
        wakeLock.setReferenceCounted(false);
        mediaSession = MediaControls.create(this);
        refreshSettings();
        // 提前在 :crypto-probe 进程探测 libnx1 是否可用，避免主进程调用时被 native 直接杀进程
        CryptoSupport.install(this);
        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        AndroidCompat.registerReceiver(this, usbReceiver, filter, false);
        Log.i(TAG, "Connection service created");
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    private AudioPlayback createPlayback() {
        return new AudioPlayback(error -> handler.post(() -> {
            Log.e(TAG, "音频播放失败：" + error);
            setBoxStatus("盒子：音频播放失败：" + error);
        }));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        started = true;
        if (prefs().getBoolean(PREFERENCE, true)) {
            promoteToForeground();
        } else {
            updateBackgroundPolicy();
        }
        // 日志窗口只与设置开关有关，未连接时也要按上次状态恢复
        updateBoxLogOverlay();
        // 大灯模拟窗口同理
        updateHeadlightTestOverlay();
        // USB permission and the phone session must be re-established after process death.
        return START_NOT_STICKY;
    }

    void attach(Client newClient, int requestedWidth, int requestedHeight) {
        client = newClient;
        width = requestedWidth;
        height = requestedHeight;
        newClient.onConnectionState();
    }

    void updateVideoSettings(int requestedWidth, int requestedHeight) {
        width = requestedWidth;
        height = requestedHeight;
        if (session != null) {
            session.setDisplayParameters(width, height, VideoPreferences.frameRate(prefs()));
            refreshSettings();
        }
    }

    void setClientVisible(boolean visible) {
        clientVisible = visible;
        if (visible) {
            updateBackgroundPolicy();
            connectAttached();
            if (session != null) session.requestKeyFrame();
            if (microphoneDecodeType > 0) startMicrophone(microphoneDecodeType);
        }
    }

    void detach(Client oldClient) {
        if (client == oldClient) {
            client = null;
            clientVisible = false;
        }
    }

    BoxProtocolSession getSession() { return session; }

    void updateBackgroundPolicy() {
        if (!prefs().getBoolean(PREFERENCE, true)) {
            if (foreground) {
                stopForeground(true);
                foreground = false;
                microphoneForeground = false;
            }
            if (wakeLock.isHeld()) wakeLock.release();
            if (started) {
                stopSelf();
                started = false;
            }
        } else if (connection != null && clientVisible) {
            if (!started) {
                Intent intent = new Intent(this, ConnectionService.class);
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
                    else startService(intent);
                    started = true;
                } catch (SecurityException | IllegalStateException exception) {
                    Log.e(TAG, "Cannot start connection service", exception);
                    setBoxStatus("盒子：后台连接启动失败：" + exception.getMessage());
                    return;
                }
            }
            promoteToForeground();
        }
    }

    private void promoteToForeground() {
        int types = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0;
        boolean microphone = clientVisible
                && AndroidCompat.hasMicrophone(this);
        if (Build.VERSION.SDK_INT >= 30 && (microphone || microphoneForeground)) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "后台连接", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 21) builder.setCategory(Notification.CATEGORY_SERVICE);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("PlayAuto 后台连接")
                .setContentText("保持盒子和手机连接，点击返回 PlayAuto")
                .setContentIntent(open).setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, types);
            else startForeground(NOTIFICATION_ID, notification);
            foreground = true;
            microphoneForeground |= microphone;
            if (connection != null && !wakeLock.isHeld()) wakeLock.acquire();
            Log.i(TAG, "Foreground connection active; microphone=" + microphoneForeground);
        } catch (SecurityException | IllegalStateException exception) {
            Log.e(TAG, "Cannot start foreground connection", exception);
            setBoxStatus("盒子：后台连接启动失败：" + exception.getMessage());
            stopSelf();
            started = false;
        }
    }

    void connectAttached() {
        if (connection != null) return;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (!isTarget(device)) continue;
            Log.i(TAG, "检测到目标 USB，检查授权");
            if (usbManager.hasPermission(device)) {
                openDevice(device);
            } else if (clientVisible && !device.getDeviceName().equals(permissionDeviceName)) {
                tryLegacyUsbPermission(device);
                if (usbManager.hasPermission(device)) {
                    permissionDeviceName = null;
                    openDevice(device);
                    return;
                }
                PendingIntent permission = PendingIntent.getBroadcast(this, device.getDeviceId(),
                        new Intent(USB_PERMISSION).setPackage(getPackageName()),
                        PendingIntent.FLAG_CANCEL_CURRENT
                                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                permissionDeviceName = device.getDeviceName();
                try {
                    Log.i(TAG, "向系统申请 USB 授权");
                    usbManager.requestPermission(device, permission);
                    usbPermissionDiagnostic = "已向系统申请 USB 授权，等待回调";
                    Log.i(TAG, usbPermissionDiagnostic + " device=" + device.getDeviceName()
                            + " vid=" + device.getVendorId() + " pid=" + device.getProductId());
                    setBoxStatus("盒子：等待 USB 授权…");
                } catch (SecurityException | IllegalArgumentException exception) {
                    permissionDeviceName = null;
                    usbPermissionDiagnostic = "申请授权失败：" + exception.getClass().getSimpleName()
                            + "：" + exception.getMessage();
                    Log.e(TAG, usbPermissionDiagnostic, exception);
                    Log.e(TAG, usbPermissionDiagnostic, exception);
                    setBoxStatus("盒子：" + usbPermissionDiagnostic);
                }
            }
            return;
        }
    }

    private void tryLegacyUsbPermission(UsbDevice device) {
        if (Build.VERSION.SDK_INT > 20) {
            legacyUsbPermissionDiagnostic = "跳过旧系统授权：仅适用于 Android 4.4";
            return;
        }
        if (!hasUsbManagementPermission()) {
            legacyUsbPermissionDiagnostic = "跳过旧系统授权：未获得 MANAGE_USB 系统权限";
            Log.w(TAG, legacyUsbPermissionDiagnostic);
            return;
        }
        Log.i(TAG, "检查旧系统 USB 授权原生库");
        if (!BoxCore.isAvailable()) {
            legacyUsbPermissionDiagnostic = "旧系统授权不可用：libnx1 加载失败："
                    + BoxCore.getLoadError();
            Log.e(TAG, legacyUsbPermissionDiagnostic);
            Log.e(TAG, legacyUsbPermissionDiagnostic);
            return;
        }
        try {
            // Match the original app, but trust the framework's permission state, not JNI's return.
            Log.i(TAG, "调用旧系统 JNI USB 授权 BoxCore.stUsb");
            BoxCore.stUsb(device, usbManager);
            legacyUsbPermissionDiagnostic = usbManager.hasPermission(device)
                    ? "旧系统授权成功：系统已确认 USB 权限"
                    : "旧系统授权未生效：继续申请系统弹窗";
            Log.i(TAG, legacyUsbPermissionDiagnostic);
        } catch (LinkageError | SecurityException exception) {
            legacyUsbPermissionDiagnostic = "旧系统授权失败："
                    + exception.getClass().getSimpleName() + "：" + exception.getMessage();
            Log.e(TAG, legacyUsbPermissionDiagnostic, exception);
            Log.e(TAG, legacyUsbPermissionDiagnostic, exception);
        }
    }

    private boolean hasUsbManagementPermission() {
        return checkPermission(MANAGE_USB, android.os.Process.myPid(), android.os.Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }



    boolean isNativeUsbConnected() {
        return connection instanceof NativeUsbConnection;
    }

    /**
     * 连接失败时的可读诊断信息，供「连接详情」直接展示。
     *
     * <p>车机（尤其 Android 4.4）上拿不到 logcat，所以把判定失败所依赖的状态
     * 全部摊开：系统与机型、是否系统应用、USB 设备枚举结果、各条授权路径的
     * 最近结果、当前通道以及原生加密库是否可用。
     */
    String usbDiagnostics() {
        StringBuilder text = new StringBuilder();
        text.append("Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）")
                .append("\n机型：").append(Build.MODEL);

        boolean systemApp = false;
        try {
            ApplicationInfo info = getPackageManager()
                    .getApplicationInfo(getPackageName(), 0);
            systemApp = (info.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException ignored) {
            // 本应用必然已安装，忽略即可
        }
        text.append("\n系统应用：").append(systemApp ? "是" : "否")
                .append("；MANAGE_USB：")
                .append(hasUsbManagementPermission() ? "已授予" : "未授予");

        text.append("\n\nUSB 设备：");
        int found = 0;
        int targets = 0;
        if (usbManager == null) {
            text.append("\n  USB 服务不可用（UsbManager 为空）");
        } else {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                found++;
                boolean target = isTarget(device);
                if (target) targets++;
                text.append("\n  ")
                        .append(String.format(java.util.Locale.US, "%04X:%04X",
                                device.getVendorId(), device.getProductId()))
                        .append(target ? "（盒子）" : "（非盒子）")
                        .append(device.getDeviceName() == null ? "" : " " + device.getDeviceName())
                        .append(target
                                ? "，权限：" + (usbManager.hasPermission(device) ? "有" : "无") : "");
            }
        }
        if (found == 0) text.append("\n  系统未枚举到任何 USB 设备");
        else if (targets == 0) text.append("\n  未发现目标盒子（需要 VID 1314，PID 1520/1521）");

        text.append("\n\n当前通道：").append(connection == null
                ? "未连接" : connection.name()
                        + (deviceName == null ? "" : "（" + deviceName + "）"));
        text.append("\n系统授权：").append(usbPermissionDiagnostic);
        text.append("\n旧系统授权：").append(legacyUsbPermissionDiagnostic);
        text.append("\n").append(rootUsbPermissionDiagnostic);
        text.append("\n原生加密库：").append(CryptoSupport.describe());
        if (!diagnosticLog.isEmpty()) {
            text.append("\n\n非致命问题（不影响连接）：");
            for (String line : diagnosticLog) text.append("\n  ").append(line);
        }
        return text.toString();
    }

    void requestRootUsbPermission() {
        if (rootUsbPending) {
            Log.w(TAG, "Root USB authorization already pending");
            setBoxStatus("Root USB：已有请求正在进行，请等待完成");
            return;
        }
        if (!clientVisible) {
            rootUsbPermissionDiagnostic = "Root USB：请在应用前台手动申请";
            setBoxStatus(rootUsbPermissionDiagnostic);
            return;
        }
        UsbDevice target = null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (!isTarget(device)) continue;
            if (target != null) {
                rootUsbPermissionDiagnostic = "Root USB：检测到多个盒子，请仅连接一个后重试";
                setBoxStatus(rootUsbPermissionDiagnostic);
                return;
            }
            target = device;
        }
        if (target == null) {
            rootUsbPermissionDiagnostic = "Root USB：未检测到支持的 USB 盒子，未执行 su";
            setBoxStatus(rootUsbPermissionDiagnostic);
            return;
        }
        if (usbManager.hasPermission(target)) {
            rootUsbPermissionDiagnostic = "Root USB：盒子已有 USB 权限，无需执行 su";
            setBoxStatus(rootUsbPermissionDiagnostic);
            connectAttached();
            return;
        }
        UsbDevice requested = target;
        rootUsbPending = true;
        rootUsbPermissionDiagnostic = "Root USB：正在请求，请在 root 管理器中确认（最多 30 秒）";
        setBoxStatus(rootUsbPermissionDiagnostic);
        RootUsbAuthorization.request(this, requested, result -> {
            rootUsbPending = false;
            if (destroyed) return;
            UsbDevice current = usbManager.getDeviceList().get(requested.getDeviceName());
            boolean granted = current != null && current.getVendorId() == requested.getVendorId()
                    && current.getProductId() == requested.getProductId() && usbManager.hasPermission(current);
            rootUsbPermissionDiagnostic = "Root USB：" + (granted
                    ? "系统已确认 USB 授权" : "未获得 USB 授权") + "\n" + result;
            Log.i(TAG, rootUsbPermissionDiagnostic);
            setBoxStatus(granted ? "盒子：Root USB 授权成功" : "盒子：Root USB 授权失败，请查看连接详情");
            if (granted && clientVisible) {
                permissionDeviceName = null;
                connectAttached();
            }
        });
    }

    void retryUsbPermission() {
        if (!clientVisible) {
            Log.w(TAG, "USB permission retry requires a visible Activity");
            setBoxStatus("盒子：请返回应用前台重试 USB 授权");
            return;
        }
        if (connection != null) {
            Log.i(TAG, "USB already connected; permission retry unnecessary");
            return;
        }
        permissionDeviceName = null;
        boolean found = false;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isTarget(device)) found = true;
        }
        if (!found) {
            setBoxStatus("盒子：未检测到支持的 USB 设备，请重新插拔");
            return;
        }
        connectAttached();
    }

    static boolean isTarget(UsbDevice device) {
        return device.getVendorId() == 0x1314
                && (device.getProductId() == 0x1520 || device.getProductId() == 0x1521);
    }

    private void openDevice(UsbDevice device) {
        if (connection != null || !isTarget(device)) return;
        try {
            Log.i(TAG, "打开 Android USB 设备");
            connection = UsbConnection.open(usbManager, device);
        } catch (IOException | SecurityException | IllegalArgumentException exception) {
            Log.e(TAG, "Android USB 打开失败，改用原生 USB 通道", exception);
            try {
                // 部分 Android 4.x 车机的框架通道拿不到端点或 claim 失败，
                // 原版应用在这些机器上走的就是 libusb（libconnection）。
                connection = NativeUsbConnection.open(
                        device.getVendorId(), device.getProductId());
            } catch (IOException | LinkageError fallback) {
                Log.e(TAG, "Android USB 连接初始化失败", exception);
                Log.e(TAG, "Cannot open USB device", exception);
                closeConnection();
                setBoxStatus("盒子：连接失败：" + exception.getMessage()
                        + "；原生通道：" + fallback.getMessage());
                // 已授权（或 4.4 系统应用可走 MANAGE_USB 静默授权）时才自动重试；
                // 需要用户点系统弹窗的情况交给用户操作。
                if (usbManager != null && (usbManager.hasPermission(device)
                        || (Build.VERSION.SDK_INT <= 20 && hasUsbManagementPermission()))) {
                    scheduleReconnect();
                }
                return;
            }
        }
        try {
            deviceName = device.getDeviceName();
            Log.i(TAG, "初始化连接音频");
            playback.close();
            playback = createPlayback();
            updateBackgroundPolicy();
            setBoxStatus("盒子：" + connection.name() + " 通道已建立");
            setPhoneStatus("手机：等待盒子状态");
            startProtocol();
            cancelReconnect();
        } catch (SecurityException | IllegalArgumentException exception) {
            Log.e(TAG, "USB 连接初始化失败", exception);
            closeConnection();
            Log.e(TAG, "Cannot open USB device", exception);
            setBoxStatus("盒子：连接失败：" + exception.getMessage());
        }
    }

    private void dispatch(int epoch, Runnable action) {
        handler.post(() -> {
            if (generation == epoch) action.run();
        });
    }

    private void startProtocol() {
        Log.i(TAG, "初始化协议会话及盒子配置");
        int epoch = ++generation;
        // 调试日志：分隔不同会话，并开始每秒汇总高频帧计数
        handler.removeCallbacks(boxFrameFlush);
        dashboardSubtypesSeen.clear();
        appendBoxCommandLine(boxLogTime.format(new java.util.Date()) + "  —— 新会话 ——");
        handler.postDelayed(boxFrameFlush, 1000);
        AudioPlayback sessionPlayback = playback;
        session = new BoxProtocolSession(connection, width, height,
                VideoPreferences.frameRate(prefs()), new BoxProtocolSession.Listener() {
            @Override public void onBoxName(boolean bluetooth, String name) {
                dispatch(epoch, () -> {
                    if (bluetooth) boxBluetoothName = name;
                    else boxWifiName = name;
                    notifyClient();
                });
            }
            @Override public void onStatus(String status) {
                dispatch(epoch, () -> setBoxStatus("盒子：" + status));
            }
            @Override public void onError(String message, Throwable error) {
                dispatch(epoch, () -> {
                    Log.e(TAG, message, error);
                    notifyClient();
                });
            }

            @Override public void onPhoneStatus(String status) {
                dispatch(epoch, () -> setPhoneStatus("手机：" + status));
            }
            @Override public void onVideoFrame(VideoFrame frame) {
                if (!clientVisible) return;
                dispatch(epoch, () -> {
                    if (client != null && clientVisible) client.onVideoFrame(frame);
                });
            }
            @Override public void onDashboardFrame(VideoFrame frame) {
                // 副屏窗口常驻，不受主界面可见性影响；帧直接投递到解码队列，
                // 避免每帧都经主线程 Handler 中转。
                SecondaryScreenOverlay overlay = secondaryScreen;
                if (overlay != null && overlay.isShowing()) {
                    overlay.submitFrame(frame);
                    return;
                }
                dispatch(epoch, () -> {
                    if (generation != epoch) return;
                    if (!prefs().getBoolean(SecondaryScreenOverlay.PREF_ENABLED, false)) return;
                    showSecondaryScreen();
                    SecondaryScreenOverlay created = secondaryScreen;
                    if (created != null && created.isShowing()) created.submitFrame(frame);
                });
            }
            @Override public void onAudioFrame(AudioFrame frame) {
                if (generation == epoch) sessionPlayback.submit(frame);
            }
            @Override public void onAudioStreamStopped(int decodeType, int audioType) {
                if (generation == epoch) sessionPlayback.stopStream(decodeType, audioType);
            }
            @Override public void onMicrophoneStart(int decodeType) {
                dispatch(epoch, () -> startMicrophone(decodeType));
            }
            @Override public void onMicrophoneStop() {
                dispatch(epoch, () -> {
                    microphoneDecodeType = -1;
                    capturingDecodeType = -1;
                    capture.stop();
                    setPhoneStatus("手机：麦克风已停止");
                });
            }
            @Override public void onLinkState(boolean plugged, boolean bluetooth, boolean wifi) {
                dispatch(epoch, () -> {
                    if (plugged && !phonePlugged) {
                        clearNavigationState("手机已连接，等待盒子上报导航数据");
                    }
                    phonePlugged = plugged;
                    bluetoothLinked = bluetooth;
                    wifiLinked = wifi;
                    if (!plugged) {
                        clearNavigationState("手机未连接，等待导航数据");
                        mediaText.clear();
                        pushDashboardText();
                        microphoneDecodeType = -1;
                        capturingDecodeType = -1;
                        capture.stop();
                    }
                    notifyClient();
                });
            }
            @Override public void onBoxVersion(String version) {
                dispatch(epoch, () -> { boxVersion = version; notifyClient(); });
            }
            @Override public void onPairedDevices(
                    java.util.List<BoxProtocolSession.PairedDevice> devices) {
                dispatch(epoch, ConnectionService.this::notifyClient);
            }
            @Override public void onBoxCommand(int command, int payloadLength, int value) {
                recordBoxCommand(command, payloadLength, value);
            }
            @Override public void onFirmwareProgress(int percent) {
                dispatch(epoch, () -> {
                    firmwareProgress = percent;
                    setBoxStatus("盒子：固件升级中 " + percent + "%");
                });
            }
            @Override public void onFirmwareStatus(int status) {
                dispatch(epoch, () -> {
                    firmwareStatus = status;
                    if (status == 1 || status == 5) {
                        firmwareProgress = 0;
                    } else if (status == 2 || status == 6) {
                        firmwareProgress = 100;
                    } else if (status == 3 || status == 7) {
                        firmwareProgress = -1;
                    }
                    setBoxStatus("盒子：固件" + BoxProtocolSession.describeFirmwareStatus(status));
                });
            }
            @Override public void onBoxInfo(String uuid, String manufacturer, String productType,
                                            String firmwareName) {
                dispatch(epoch, () -> {
                    boxUuid = uuid;
                    boxManufacturer = manufacturer;
                    boxProductType = productType;
                    boxFirmwareName = firmwareName;
                    notifyClient();
                });
            }
            @Override public void onNavigationData(String data) {
                dispatch(epoch, () -> recordNavigationData(data));
            }
            @Override public void onNavigationUpdate(org.json.JSONObject data) {
                dispatch(epoch, () -> updateNavigationText(data));
            }
            @Override public void onNavigationStarted() {
                dispatch(epoch, () -> {
                    navigationText.markStarted(System.currentTimeMillis());
                    pushDashboardText();
                });
            }
            @Override public void onMediaUpdate(org.json.JSONObject data) {
                dispatch(epoch, () -> updateMediaText(data));
            }
            @Override public void onNavigationEnded() {
                dispatch(epoch, () -> {
                    navigationText.markEnded(System.currentTimeMillis());
                    pushDashboardText();
                    // 防抖：3 秒内没有新的导航数据才真正清空，避免 2/0 交替导致闪烁
                    handler.removeCallbacks(dashboardTextRefresh);
                    handler.postDelayed(dashboardTextRefresh,
                            NavigationText.CLEAR_DEBOUNCE_MS + 200);
                    if (client != null) client.onNavigationLogChanged();
                });
            }
            @Override public void onHostUiRequested() {
                dispatch(epoch, () -> {
                    if (client != null && clientVisible) {
                        client.onHostUiRequested();
                    } else {
                        Log.w(TAG, "CarPlay host UI request ignored: no visible Activity");
                    }
                });
            }
            @Override public void onSessionEnded() {
                dispatch(epoch, () -> {
                    String failure = boxStatus;
                    closeConnection();
                    setBoxStatus(failure);
                    Log.w(TAG, "Protocol session ended; USB/audio and wake lock released");
                    // 会话异常结束通常只是一次 USB 抖动：盒子本身仍在工作，
                    // 若不自动重试，界面会一直停在「连接失败，请重新插拔设备」。
                    scheduleReconnect();
                });
            }
            @Override public void onDiagnostic(String message) {
                dispatch(epoch, () -> recordDiagnostic(message));
            }
        });
        refreshSettings();
        session.start();
        Log.i(TAG, "协议线程已启动，准备汽车图标");
        syncCarLogo();
        notifyClient();
    }

    /**
     * 把固件镜像下发给盒子。盒子随后通过 177/187 回报进度与结果，
     * 进度会经 {@link #firmwareProgress} / {@link #firmwareStatus} 暴露给界面。
     */
    boolean sendFirmwareImage(byte[] image) {
        BoxProtocolSession current = session;
        if (current == null) {
            setBoxStatus("盒子：未连接，无法下发固件");
            return false;
        }
        firmwareProgress = 0;
        firmwareStatus = 0;
        notifyClient();
        return current.sendFirmwareImage(image);
    }

    /**
     * 主动要求盒子重启（{@code CMD_BOX_REBOOT} 206）。
     *
     * <p>固件升级成功后由协议层自动调用；设置页也提供手动入口，
     * 用于盒子卡死或需要验证命令是否生效的场景。
     */
    boolean rebootBox() {
        BoxProtocolSession current = session;
        if (current == null) {
            setBoxStatus("盒子：未连接，无法发送重启指令");
            return false;
        }
        // 升级过程中（187=1/5）重启盒子会损坏固件，必须拦住。
        if (firmwareStatus == 1 || firmwareStatus == 5) {
            setBoxStatus("盒子：固件升级中，不能重启");
            return false;
        }
        return current.rebootBox();
    }

    /**
     * 点名让盒子回连某台已配对手机（{@code CMD_AUTO_CONN_BT_ADDR} 17）。
     *
     * @param mac 形如 {@code AA:BB:CC:DD:EE:FF} 的蓝牙地址
     */
    boolean autoConnectToDevice(String mac) {
        BoxProtocolSession current = session;
        if (current == null) {
            setBoxStatus("盒子：未连接，无法指定回连手机");
            return false;
        }
        return current.requestAutoConnectTo(mac);
    }

    /** 盒子上报的已配对手机列表，界面用于「指定回连手机」的选择。 */
    java.util.List<BoxProtocolSession.PairedDevice> pairedDevices() {
        BoxProtocolSession current = session;
        if (current == null) {
            return java.util.Collections.emptyList();
        }
        return current.getPairedDevices();
    }

    /**
     * 从盒子的已配对列表里删除一台手机（{@code CMD_BLUETOOTH_PAIR_LIST} 18 回写）。
     *
     * @param mac 目标手机的蓝牙地址
     */
    boolean removePairedDevice(String mac) {
        BoxProtocolSession current = session;
        if (current == null) {
            setBoxStatus("盒子：未连接，无法修改配对列表");
            return false;
        }
        return current.removePairedDevice(mac);
    }

    /**
     * 要求盒子断开当前连接的手机（{@code CMD_MANUAL_DISCONNECT} 15）。
     *
     * <p>与「指定回连手机」互逆：17 是把某台手机连上，15 是把当前手机踢掉。
     */
    boolean manualDisconnectPhone() {
        BoxProtocolSession current = session;
        if (current == null) {
            setBoxStatus("盒子：未连接，无法断开手机");
            return false;
        }
        return current.requestManualDisconnect();
    }

    // ---- 副屏悬浮窗 ----

    /** 按设置开关与悬浮窗权限决定副屏窗口是否显示。 */
    private void updateSecondaryScreen() {
        if (destroyed) {
            return;
        }
        if (!prefs().getBoolean(SecondaryScreenOverlay.PREF_ENABLED, false)) {
            hideSecondaryScreen();
            return;
        }
        if (!AndroidCompat.canDrawOverlays(this)) {
            Log.w(TAG, "副屏悬浮窗已开启，但缺少「显示在其他应用上层」权限");
            hideSecondaryScreen();
            return;
        }
        showSecondaryScreen();
    }

    private void showSecondaryScreen() {
        if (destroyed) {
            return;
        }
        if (secondaryScreen == null) {
            secondaryScreen = new SecondaryScreenOverlay(this, new SecondaryScreenOverlay.WindowListener() {
                @Override public void onCloseRequested() {
                    prefs().edit().putBoolean(SecondaryScreenOverlay.PREF_ENABLED, false).apply();
                    hideSecondaryScreen();
                    notifyClient();
                }

                @Override public void onLayoutChanged(int widthPx, int heightPx) {
                    publishNaviScreenInfo(widthPx, heightPx);
                }
            });
        }
        if (!secondaryScreen.isShowing()) {
            secondaryScreen.show();
            secondaryScreen.notifyLayout();
        }
        // 窗口刚显示时把当前导航/歌词文字补上
        pushDashboardText();
    }

    private void hideSecondaryScreen() {
        SecondaryScreenOverlay current = secondaryScreen;
        if (current != null && current.isShowing()) {
            current.hide();
        }
    }

    /** 把副屏尺寸同步给盒子（{@code CMD_BOX_SETTINGS} 25 的 naviScreenInfo）。 */
    private void publishNaviScreenInfo(int widthPx, int heightPx) {
        BoxProtocolSession current = session;
        if (current == null || widthPx <= 0 || heightPx <= 0) {
            return;
        }
        SharedPreferences preferences = prefs();
        current.sendNaviScreenInfo(widthPx, heightPx,
                VideoPreferences.frameRate(preferences),
                VideoPreferences.dpi(preferences, width, height));
    }

    /** 设置页切换副屏开关后调用。 */
    void refreshSecondaryScreen() {
        updateSecondaryScreen();
    }

    /** 设置页切换副屏大小档位后调用；手动拖动缩放过的宽度会被预设覆盖。 */
    void applySecondaryScreenScale() {
        updateSecondaryScreen();
        SecondaryScreenOverlay current = secondaryScreen;
        if (current != null && current.isShowing()) {
            current.applyScale(prefs().getInt(SecondaryScreenOverlay.PREF_SCALE, 1));
        }
    }

    void syncCarLogo() {
        BoxProtocolSession current = session;
        if (current == null) return;
        String path = prefs().getString("CarPlayOEMIconPath", CarLogoUpload.DEFAULT_PATH);
        new Thread(() -> {
            try {
                CarLogoUpload.send(this, current, path);
            } catch (IOException | org.json.JSONException exception) {
                Log.e(TAG, "Cannot upload car logo", exception);
                Log.e(TAG, "读取或上传汽车图标失败", exception);
                handler.post(() -> {
                    if (session == current) setBoxStatus("盒子：读取汽车图标失败：" + exception.getMessage());
                });
            }
        }, "autoplay-car-logo-prepare").start();
    }

    void refreshSettings() {
        SharedPreferences prefs = prefs();
        boolean headlightLinked = prefs.getInt("night_mode", 2) == 3;
        if (headlightLinked) headlightNightMode.start();
        else headlightNightMode.stop();
        reportHeadlightState(headlightLinked);
        playback.setOutputGain(prefs.getInt("audio_output_gain", 100) / 100f);
        playback.setNavigationMediaGain(prefs.getInt("navigation_media_gain", 100) / 100f);
        capture.setGainDb(prefs.getInt("mic_gain_db", 0));
        updateMediaSession();
        // 大灯模拟窗口与连接状态无关：未连盒子时也要能打开排查
        updateHeadlightTestOverlay();
        BoxProtocolSession current = session;
        if (current == null) return;
        // 连接模式固定自动；麦克风固定使用汽车麦克风；驾驶方向固定左舵。
        current.setAutoConnectEnabled(true);
        current.setNightMode(currentNightSetting());
        current.setMicSource(0);
        current.setAutoPlayMusic(prefs.getBoolean("auto_play_music", false));
        current.setRebootAfterUpgrade(prefs.getBoolean("reboot_after_upgrade", true));
        // 副屏窗口与协议无关，连接建立后按开关恢复；尺寸变化会回调 naviScreenInfo。
        updateSecondaryScreen();
        // 调试日志窗口同样按开关恢复，不依赖连接状态
        updateBoxLogOverlay();
        List<BoxSetting> settings = new ArrayList<>();
        settings.add(BoxSetting.dashboardInfo());
        settings.add(BoxSetting.integer("drivePosition", 0));
        if (prefs.contains("media_delay")) settings.add(BoxSetting.integer("mediaDelay", prefs.getInt("media_delay", 0)));
        if (prefs.contains("dock_position")) settings.add(BoxSetting.integer("DockPosition", prefs.getInt("dock_position", 0)));
        settings.add(BoxSetting.screenDpi(VideoPreferences.dpi(prefs, width, height)));
        if (prefs.contains("box_bt_name")) settings.add(BoxSetting.name(true, prefs.getString("box_bt_name", "")));
        if (prefs.contains("box_wifi_name")) settings.add(BoxSetting.name(false, prefs.getString("box_wifi_name", "")));
        if (prefs.contains("wifi_band")) settings.add(BoxSetting.wifiBand(prefs.getInt("wifi_band", 1) == 1));
        if (prefs.contains("audio_transfer")) settings.add(BoxSetting.audioTransfer(prefs.getBoolean("audio_transfer", false)));
        if (prefs.contains("use_bt_phone")) settings.add(BoxSetting.integer("UseBTPhone", prefs.getBoolean("use_bt_phone", false) ? 1 : 0));
        current.setBoxSettings(settings);
    }

    private int currentNightSetting() {
        int mode = prefs().getInt("night_mode", 2);
        if (mode == 3) return headlightNightMode.currentSetting();
        // 设置页只保留「跟随手机」与「大灯联动」；历史值 0/1（开/关）统一按跟随手机处理。
        return -1;
    }

    private void onHeadlightChanged() {
        // 大灯状态变化（真实或模拟）都要刷新调试窗口里的显示
        refreshHeadlightTestStatus();
        if (destroyed || prefs().getInt("night_mode", 2) != 3) return;
        BoxProtocolSession current = session;
        if (current != null) current.setNightMode(headlightNightMode.currentSetting());
    }

    void retryMicrophone() {
        if (microphoneDecodeType > 0) startMicrophone(microphoneDecodeType);
    }

    private void startMicrophone(int decodeType) {
        microphoneDecodeType = decodeType;
        if (session == null || decodeType < 1) return;
        if (!prefs().getBoolean("microphone_enabled", true)) {
            setPhoneStatus("手机：麦克风回传已关闭");
            return;
        }
        if (capturingDecodeType == decodeType) return;
        if (!AndroidCompat.hasMicrophone(this)) {
            setPhoneStatus("手机：等待麦克风权限，请返回 PlayAuto 授权");
            if (client != null && clientVisible) client.onMicrophonePermissionNeeded();
            return;
        }
        if (foreground && clientVisible && !microphoneForeground) promoteToForeground();
        if (!clientVisible && Build.VERSION.SDK_INT >= 30 && !microphoneForeground) {
            setPhoneStatus("手机：后台麦克风不可用，请返回 PlayAuto");
            return;
        }
        try {
            BoxProtocolSession microphoneSession = session;
            capture.start(decodeType, (format, data) -> {
                BoxProtocolSession current = session;
                if (current != null && current == microphoneSession) current.sendMicrophonePcm(format, data);
            });
            capturingDecodeType = decodeType;
            setPhoneStatus("手机：麦克风正在向盒子传输");
        } catch (IOException | SecurityException exception) {
            Log.e(TAG, "Cannot start microphone", exception);
            setPhoneStatus("手机：无法启动麦克风：" + exception.getMessage());
        }
    }

    boolean sendMediaCommand(int command) {
        BoxProtocolSession current = session;
        return current != null && current.sendMediaCommand(SteeringProtocol.playbackAction(prefs(), command));
    }

    private int lastSteeringCommand = -1;
    private String lastSteeringSource = "";
    private long lastSteeringTime;

    synchronized boolean sendSteeringCommand(int command, String source) {
        long now = android.os.SystemClock.elapsedRealtime();
        int canonical = command == 204 ? 10002 : command == 205 ? 10001 : command;
        if (canonical == lastSteeringCommand && !source.equals(lastSteeringSource)
                && now - lastSteeringTime < 350) {
            Log.i("AutoPlayKeys", "Duplicate steering event suppressed: " + source);
            return true;
        }
        int action = SteeringProtocol.vendorEnabled(prefs()) ? canonical : command;
        Log.i(TAG, "方控来源=" + source + "，原始动作=" + command
                + "，分发动作=" + action + "，长安协议=" + SteeringProtocol.vendorEnabled(prefs()));
        if (!sendMediaCommand(action)) {
            Log.w("AutoPlayKeys", "Steering command rejected: " + source + " action=" + command);
            return false;
        }
        lastSteeringCommand = canonical;
        lastSteeringSource = source;
        lastSteeringTime = now;
        return true;
    }

    int mediaCommand(int keyCode) {
        return mediaCommand(keyCode, false);
    }

    /**
     * 查询按键对应的功能号。
     *
     * @param longPress {@code true} 查询长按绑定；内置默认行为只提供单击
     * @return 功能号；无对应行为时为 -1
     */
    int mediaCommand(int keyCode, boolean longPress) {
        int learned = SteeringProtocol.androidCommand(prefs(), keyCode, longPress);
        if (learned >= 0) return learned;
        // 内置默认只定义单击语义，长按必须由用户显式学习。
        if (longPress) return -1;
        // 音量键不再默认映射到上一曲/下一曲；如需该行为，请在「方控学习」中显式绑定。
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY: return 201;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return 202;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return 203;
            case KeyEvent.KEYCODE_MEDIA_NEXT: return 204;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return 205;
            default: return -1;
        }
    }

    void updateMediaSession() {
        if (mediaSession != null) mediaSession.setActive(phonePlugged
                && prefs().getBoolean("background_control", false));
    }

    private void setBoxStatus(String status) {
        boxStatus = status;
        notifyClient();
    }

    private final java.util.ArrayDeque<String> diagnosticLog = new java.util.ArrayDeque<>();
    private int reconnectAttempt;
    private static final long[] RECONNECT_DELAYS_MS = {2000L, 4000L, 8000L, 15000L, 30000L};
    /** 自动重连上限：约 2+4+8+15+30×6 ≈ 3.5 分钟后转为等待用户操作。 */
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private final Runnable reconnectRunnable = () -> {
        if (destroyed || connection != null) {
            reconnectAttempt = 0;
            return;
        }
        if (!hasTargetDevice()) {
            // 盒子已拔出：不再重试，等 USB_DEVICE_ATTACHED 广播
            reconnectAttempt = 0;
            return;
        }
        Log.i(TAG, "自动重连第 " + (reconnectAttempt + 1) + " 次");
        // 重试的收尾由 openDevice 决定：成功即清零，失败会再排一次。
        connectAttached();
    };

    private boolean hasTargetDevice() {
        if (usbManager == null) return false;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isTarget(device)) return true;
        }
        return false;
    }

    /** 非致命问题留档，供「连接详情」查看，不改变连接状态。 */
    void recordDiagnostic(String message) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + "  " + message;
        diagnosticLog.addLast(line);
        while (diagnosticLog.size() > 20) diagnosticLog.removeFirst();
        Log.i(TAG, "诊断：" + message);
    }

    // ---- 盒子指令日志（调试窗口） ----

    /** 日志保留条数，与导航日志一致量级，避免长时间运行吃内存。 */
    private static final int MAX_BOX_COMMAND_LOG = 200;
    /** 最新一条在最前。只在主线程读写。 */
    private final java.util.ArrayDeque<String> boxCommandLog = new java.util.ArrayDeque<>();
    /** 高频帧计数：视频 6 / 音频 7 / 副屏 44，每秒汇总一行。 */
    private final java.util.concurrent.atomic.AtomicInteger videoFrameCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger audioFrameCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger dashboardFrameCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.text.SimpleDateFormat boxLogTime =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT);
    private final Runnable boxFrameFlush = this::flushBoxFrameCounts;
    /** 本次会话收到的 cmd 42 子类型（去重，保持到达顺序）。只在主线程读写。 */
    private final java.util.LinkedHashSet<Integer> dashboardSubtypesSeen =
            new java.util.LinkedHashSet<>();

    /**
     * 记录一条盒子→主机命令。
     *
     * <p>在协议线程调用。视频/音频/副屏帧只累加计数，由
     * {@link #flushBoxFrameCounts()} 每秒汇总成一行，避免日志刷屏。
     */
    private void recordBoxCommand(int command, int payloadLength, int value) {
        switch (command) {
            case 6:
                videoFrameCount.incrementAndGet();
                return;
            case 7:
                audioFrameCount.incrementAndGet();
                return;
            case 44:
                dashboardFrameCount.incrementAndGet();
                return;
            default:
                break;
        }
        // 时间格式化与环形缓冲都只在主线程做（SimpleDateFormat 非线程安全）
        handler.post(() -> {
            if (command == 42 && value >= 0) {
                dashboardSubtypesSeen.add(value);
                if (client != null) client.onNavigationLogChanged();
            }
            appendBoxCommandLine(formatBoxCommand(command, payloadLength, value));
        });
    }

    /** 本次会话已收到的 cmd 42 子类型，用于诊断「只有歌词没有导航」。 */
    String dashboardSubtypeSummary() {
        if (dashboardSubtypesSeen.isEmpty()) {
            return "尚未收到 CMD 42 数据";
        }
        StringBuilder text = new StringBuilder("已收到 CMD 42 子类型：");
        boolean first = true;
        for (Integer subtype : dashboardSubtypesSeen) {
            if (!first) text.append("、");
            first = false;
            text.append(subtype).append('（').append(dashboardSubtypeName(subtype)).append('）');
        }
        if (!dashboardSubtypesSeen.contains(200)) {
            text.append("\n没有 200（导航）：盒子只在手机处于真实路线引导、"
                    + "且车机屏幕显示导航时才会下发");
        }
        return text.toString();
    }

    private static String dashboardSubtypeName(int subtype) {
        switch (subtype) {
            case 1: return "媒体/歌词";
            case 2: return "封面";
            case 3: return "封面";
            case 100: return "来电";
            case 200: return "导航";
            case 202: return "原始";
            case 203: return "原始";
            default: return "未知";
        }
    }

    private String formatBoxCommand(int command, int payloadLength, int value) {
        String name = BoxProtocolSession.describeCommand(command);
        StringBuilder line = new StringBuilder().append(boxLogTime.format(new java.util.Date()))
                .append("  ").append(command);
        if (name == null) {
            line.append("  未知命令");
        } else {
            line.append("  ").append(name);
        }
        line.append("  ").append(payloadLength).append(" B");
        if (value >= 0) line.append("  值=").append(value);
        return line.toString();
    }

    /** 每秒把三类高频帧的计数写成一行；无计数时跳过。 */
    private void flushBoxFrameCounts() {
        if (destroyed) return;
        int video = videoFrameCount.getAndSet(0);
        int audio = audioFrameCount.getAndSet(0);
        int dashboard = dashboardFrameCount.getAndSet(0);
        StringBuilder line = new StringBuilder();
        if (video > 0) line.append("6 CMD_VIDEO_FRAME ×").append(video).append(" 帧");
        if (audio > 0) {
            if (line.length() > 0) line.append("；");
            line.append("7 CMD_AUDIO_FRAME ×").append(audio).append(" 帧");
        }
        if (dashboard > 0) {
            if (line.length() > 0) line.append("；");
            line.append("44 CMD_DASHBOARD_FRAME ×").append(dashboard).append(" 帧");
        }
        if (line.length() > 0) {
            String text = boxLogTime.format(new java.util.Date()) + "  " + line;
            appendBoxCommandLine(text);
        }
        // 只在会话存活时继续排下一次汇总
        if (!destroyed && session != null) handler.postDelayed(boxFrameFlush, 1000);
    }

    /** 追加一行并刷新日志窗口/界面。必须在主线程调用。 */
    private void appendBoxCommandLine(String line) {
        boxCommandLog.addFirst(line);
        while (boxCommandLog.size() > MAX_BOX_COMMAND_LOG) boxCommandLog.removeLast();
        BoxLogOverlay overlay = boxLogOverlay;
        if (overlay != null && overlay.isShowing()) {
            overlay.setText(boxCommandLogText());
        }
        if (client != null) client.onBoxCommandLogChanged();
    }

    /** 日志文本，最新在前；日志窗口和设置页共用。 */
    String boxCommandLogText() {
        if (boxCommandLog.isEmpty()) return "等待盒子上报指令…";
        StringBuilder text = new StringBuilder();
        for (String line : boxCommandLog) {
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        return text.toString();
    }

    void clearBoxCommandLog() {
        handler.post(() -> {
            boxCommandLog.clear();
            videoFrameCount.set(0);
            audioFrameCount.set(0);
            dashboardFrameCount.set(0);
            BoxLogOverlay overlay = boxLogOverlay;
            if (overlay != null && overlay.isShowing()) overlay.setText(boxCommandLogText());
            if (client != null) client.onBoxCommandLogChanged();
        });
    }

    // ---- 盒子指令日志悬浮窗 ----

    private void updateBoxLogOverlay() {
        if (destroyed) {
            return;
        }
        if (!prefs().getBoolean(BoxLogOverlay.PREF_ENABLED, false)) {
            hideBoxLogOverlay();
            return;
        }
        if (!AndroidCompat.canDrawOverlays(this)) {
            Log.w(TAG, "盒子指令日志已开启，但缺少「显示在其他应用上层」权限");
            hideBoxLogOverlay();
            return;
        }
        showBoxLogOverlay();
    }

    private void showBoxLogOverlay() {
        if (destroyed) {
            return;
        }
        if (boxLogOverlay == null) {
            boxLogOverlay = new BoxLogOverlay(this, new BoxLogOverlay.WindowListener() {
                @Override public void onCloseRequested() {
                    prefs().edit().putBoolean(BoxLogOverlay.PREF_ENABLED, false).apply();
                    hideBoxLogOverlay();
                    notifyClient();
                }

                @Override public void onClearRequested() {
                    clearBoxCommandLog();
                }
            });
        }
        if (!boxLogOverlay.isShowing()) {
            boxLogOverlay.show();
        }
        boxLogOverlay.setText(boxCommandLogText());
    }

    private void hideBoxLogOverlay() {
        BoxLogOverlay current = boxLogOverlay;
        if (current != null && current.isShowing()) {
            current.hide();
        }
    }

    /** 设置页切换「盒子指令日志」开关后调用。 */
    void refreshBoxLogOverlay() {
        updateBoxLogOverlay();
    }

    // ---- 大灯模拟（调试） ----

    private void updateHeadlightTestOverlay() {
        if (destroyed) {
            return;
        }
        if (!prefs().getBoolean(HeadlightTestOverlay.PREF_ENABLED, false)) {
            hideHeadlightTestOverlay();
            return;
        }
        if (!AndroidCompat.canDrawOverlays(this)) {
            Log.w(TAG, "大灯模拟已开启，但缺少「显示在其他应用上层」权限");
            hideHeadlightTestOverlay();
            return;
        }
        showHeadlightTestOverlay();
    }

    private void showHeadlightTestOverlay() {
        if (destroyed) {
            return;
        }
        if (headlightTestOverlay == null) {
            headlightTestOverlay = new HeadlightTestOverlay(this,
                    new HeadlightTestOverlay.WindowListener() {
                @Override public void onCloseRequested() {
                    prefs().edit()
                            .putBoolean(HeadlightTestOverlay.PREF_ENABLED, false).apply();
                    hideHeadlightTestOverlay();
                    notifyClient();
                }

                @Override public void onSimulate(int setting) {
                    simulateHeadlight(setting);
                }
            });
        }
        if (!headlightTestOverlay.isShowing()) {
            headlightTestOverlay.show();
        }
        refreshHeadlightTestStatus();
    }

    private void hideHeadlightTestOverlay() {
        HeadlightTestOverlay current = headlightTestOverlay;
        if (current != null && current.isShowing()) {
            current.hide();
        }
    }

    /** 设置页切换「大灯模拟」开关后调用。 */
    void refreshHeadlightTestOverlay() {
        updateHeadlightTestOverlay();
    }

    /** 「高级功能 → MCU 大灯探测」调用的反射报告。 */
    String mcuProbeReport() {
        return headlightNightMode.probe();
    }

    /** 上次写入连接详情的大灯状态说明，避免重复记录刷满诊断缓冲。 */
    private String lastHeadlightDiagnostic = "";
    /** 上一次检查时大灯联动是否不可用，用于记录「恢复可用」。 */
    private boolean headlightWasUnavailable;

    /**
     * 把大灯联动是否真的可用写进「连接详情」。
     *
     * <p>拿不到 MCU 状态是「夜间模式不动」最常见的原因，但表现是完全静默的：
     * 没有异常弹窗，盒子也收不到指令。这里显式留档，用户在设置页就能看到结论，
     * 不必连 adb。
     */
    private void reportHeadlightState(boolean linked) {
        if (linked && !headlightNightMode.mcuAvailable()) {
            String error = headlightNightMode.bindError();
            String message = "大灯联动不可用："
                    + (error.isEmpty() ? "尚未绑定车机 MCU" : error)
                    + "（夜间模式不会随大灯变化；可在「调试 → MCU 大灯探测」查看原因）";
            if (!message.equals(lastHeadlightDiagnostic)) {
                lastHeadlightDiagnostic = message;
                Log.w(TAG, message);
                recordDiagnostic(message);
            }
            headlightWasUnavailable = true;
            return;
        }
        if (headlightWasUnavailable) {
            headlightWasUnavailable = false;
            lastHeadlightDiagnostic = "";
            recordDiagnostic(linked ? "大灯联动已就绪：已连上车机 MCU" : "大灯联动已关闭");
        }
    }

    /** 伪造一次大灯状态，走与真实大灯完全相同的回调链路。 */
    private void simulateHeadlight(int setting) {
        int mode = prefs().getInt("night_mode", 2);
        boolean applied = headlightNightMode.simulate(setting);
        Log.i(TAG, "模拟大灯=" + (setting == 1 ? "开" : "关")
                + "，夜间模式=" + (mode == 3 ? "大灯联动" : "跟随手机")
                + "，已注入=" + applied
                + (mode == 3 ? "" : "（当前不是大灯联动，不会下发盒子）"));
        if (mode != 3) {
            setPhoneStatus("大灯模拟：夜间模式需选「大灯联动」才会下发盒子");
        }
        refreshHeadlightTestStatus();
    }

    /** 把当前大灯/夜间模式状态写进悬浮窗，便于边操作边核对。 */
    private void refreshHeadlightTestStatus() {
        HeadlightTestOverlay overlay = headlightTestOverlay;
        if (overlay == null) {
            return;
        }
        int mode = prefs().getInt("night_mode", 2);
        int light = headlightNightMode.currentSetting();
        boolean linked = mode == 3;
        boolean connected = session != null;
        StringBuilder summary = new StringBuilder();
        summary.append("大灯：")
                .append(light == 1 ? "开（夜间）" : light == 0 ? "关（白天）" : "未知")
                .append('\n')
                .append("夜间模式：")
                .append(linked ? "大灯联动" : "跟随手机（不生效）")
                .append('\n')
                .append("盒子：").append(connected ? "已连接" : "未连接")
                .append("　MCU：").append(headlightNightMode.mcuAvailable() ? "可用" : "不可用")
                .append('\n');
        if (!linked) {
            summary.append("请先在设置里把夜间模式改成「大灯联动」");
        } else if (!connected) {
            summary.append("盒子未连接，指令不会送达");
        } else {
            summary.append("开灯 → cmd 8/16（夜间），关灯 → cmd 8/17（白天）");
        }
        overlay.setStatus(light, summary.toString());
    }

    /** 会话异常结束后按退避间隔自动重试，避免一直卡在「连接失败」。 */
    private void scheduleReconnect() {        if (destroyed) return;
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "自动重连已达上限，等待用户拔插盒子或重新打开应用");
            return;
        }
        long delay = RECONNECT_DELAYS_MS[
                Math.min(reconnectAttempt, RECONNECT_DELAYS_MS.length - 1)];
        reconnectAttempt++;
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, delay);
    }

    private void cancelReconnect() {
        reconnectAttempt = 0;
        handler.removeCallbacks(reconnectRunnable);
    }

    private void setPhoneStatus(String status) {
        phoneStatus = status;
        notifyClient();
    }

    private final java.util.ArrayDeque<String> navigationLog = new java.util.ArrayDeque<>();
    private final NavigationText navigationText = new NavigationText();
    private final MediaText mediaText = new MediaText();
    /** 导航结束后延迟刷新副屏文字（防抖）。 */
    private final Runnable dashboardTextRefresh = this::pushDashboardText;
    /** 上一次推送到副屏的文字摘要，只在变化时记日志。 */
    private String lastDashboardTextLog = "";

    String navigationText() {
        return navigationText.text();
    }

    /** 媒体/歌词文字，供设置页展示。 */
    String mediaText() {
        return mediaText.text();
    }

    /** 转向原始字段与解析结果，供设置页对照车机屏幕排查图标问题。 */
    String maneuverDebugText() {
        return navigationText.debugLine();
    }

    private void clearNavigationState(String message) {
        navigationText.clear(message);
        pushDashboardText();
    }

    private void updateNavigationText(org.json.JSONObject data) {
        navigationText.update(data, System.currentTimeMillis());
        pushDashboardText();
        if (navigationText.endPending()) {
            // 盒子进入结束态后再次刷新一次，让防抖后的清空真正生效
            handler.removeCallbacks(dashboardTextRefresh);
            handler.postDelayed(dashboardTextRefresh,
                    NavigationText.CLEAR_DEBOUNCE_MS + 200);
        }
        if (client != null) client.onNavigationLogChanged();
    }

    private void updateMediaText(org.json.JSONObject data) {
        mediaText.update(data);
        pushDashboardText();
        // 设置页的「仪表导航」也展示歌词，复用导航日志的刷新回调
        if (client != null) client.onNavigationLogChanged();
    }

    /**
     * 把当前导航/歌词文字推到副屏悬浮窗。
     *
     * <p>盒子自己会在 44 流里画 HUD，这里只做叠加；悬浮窗没开时直接跳过。
     */
    private void pushDashboardText() {
        SecondaryScreenOverlay overlay = secondaryScreen;
        if (overlay == null || !overlay.isShowing()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean showNav = navigationText.shouldShow(now);
        String primary = showNav ? navigationText.primaryLine() : "";
        String secondary = showNav ? navigationText.secondaryLine() : "";
        Maneuver maneuver = showNav ? navigationText.maneuver() : Maneuver.STRAIGHT;
        String lyric = mediaText.lyricLine();
        overlay.setDashboardText(primary, secondary, maneuver, showNav, lyric);
        logDashboardText(primary, secondary, maneuver, showNav, lyric);
    }

    /** 副屏文字只在内容变化时记一条日志，便于不截图也能确认上屏内容。 */
    private void logDashboardText(String primary, String secondary, Maneuver maneuver,
            boolean showNav, String lyric) {
        String snapshot = (showNav ? primary + " | " + secondary + " | 图标 "
                + maneuver.label() : "<无导航>") + " ｜ 歌词："
                + (lyric.isEmpty() ? "<无>" : lyric);
        if (snapshot.equals(lastDashboardTextLog)) {
            return;
        }
        lastDashboardTextLog = snapshot;
        Log.i("AutoPlaySecondary", "副屏文字：" + snapshot);
    }

    String navigationLogText() {
        if (navigationLog.isEmpty()) return "等待盒子上报导航数据（命令 42 / 子类型 200）…";
        StringBuilder text = new StringBuilder();
        for (String entry : navigationLog) {
            if (text.length() > 0) text.append("\n\n");
            text.append(entry);
        }
        return text.toString();
    }

    void clearNavigationLog() {
        navigationLog.clear();
        if (client != null) client.onNavigationLogChanged();
    }

    private void recordNavigationData(String data) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
                .format(new java.util.Date());
        navigationLog.addFirst(time + "\n" + data);
        while (navigationLog.size() > 50) navigationLog.removeLast();
        if (client != null) client.onNavigationLogChanged();
    }

    private void notifyClient() {
        updateMediaSession();
        if (client != null) client.onConnectionState();
    }

    private void closeConnection() {
        cancelReconnect();
        handler.removeCallbacks(boxFrameFlush);
        handler.removeCallbacks(dashboardTextRefresh);
        mediaText.clear();
        clearNavigationState("盒子未连接，等待导航数据");
        generation++;
        BoxProtocolSession old = session;
        session = null;
        if (old != null) old.close();
        if (connection != null) connection.close();
        connection = null;
        deviceName = null;
        microphoneDecodeType = -1;
        capturingDecodeType = -1;
        capture.stop();
        playback.close();
        // 断开后副屏会停在最后一帧，先收起；重连时按开关自动恢复。
        hideSecondaryScreen();
        if (wakeLock.isHeld()) wakeLock.release();
        phonePlugged = bluetoothLinked = wifiLinked = false;
        boxVersion = null;
        boxUuid = null;
        boxManufacturer = null;
        boxProductType = null;
        boxFirmwareName = null;
        firmwareProgress = -1;
        firmwareStatus = 0;
        boxBluetoothName = boxWifiName = null;
        boxStatus = "盒子：未连接";
        phoneStatus = "手机：未连接";
        notifyClient();
    }

    @Override public void onDestroy() {
        destroyed = true;
        client = null;
        headlightNightMode.stop();
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(usbReceiver);
        closeConnection();
        SecondaryScreenOverlay overlay = secondaryScreen;
        secondaryScreen = null;
        if (overlay != null) {
            overlay.close();
        }
        BoxLogOverlay logOverlay = boxLogOverlay;
        boxLogOverlay = null;
        if (logOverlay != null) {
            logOverlay.close();
        }
        capture.close();
        mediaSession.release();
        stopForeground(true);
        Log.i(TAG, "Connection service destroyed");
        super.onDestroy();
    }
}
