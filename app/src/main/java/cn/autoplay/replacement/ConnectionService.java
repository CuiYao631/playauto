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
        void onConnectionState();
        void onVideoFrame(VideoFrame frame);
        void onMicrophonePermissionNeeded();
    }

    final class LocalBinder extends Binder {
        ConnectionService getService() { return ConnectionService.this; }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HeadlightNightMode headlightNightMode = new HeadlightNightMode(handler, this::onHeadlightChanged);
    private final LocalBinder binder = new LocalBinder();
    private UsbManager usbManager;
    private UsbTransport connection;
    private volatile BoxProtocolSession session;
    private AudioPlayback playback;
    private AudioCapture capture;
    private PowerManager.WakeLock wakeLock;
    private MediaControls mediaSession;
    private Client client;
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
            deviceName = device.getDeviceName();
            Log.i(TAG, "初始化连接音频");
            playback.close();
            playback = createPlayback();
            updateBackgroundPolicy();
            setBoxStatus("盒子：" + connection.name() + " 通道已建立");
            setPhoneStatus("手机：等待盒子状态");
            startProtocol();
        } catch (IOException | SecurityException | IllegalArgumentException exception) {
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
            @Override public void onNavigationEnded() {
                dispatch(epoch, () -> {
                    clearNavigationState("导航已结束");
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
                });
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
        if (prefs.getInt("night_mode", 2) == 3) headlightNightMode.start();
        else headlightNightMode.stop();
        playback.setOutputGain(prefs.getInt("audio_output_gain", 100) / 100f);
        playback.setNavigationMediaGain(prefs.getInt("navigation_media_gain", 100) / 100f);
        capture.setGainDb(prefs.getInt("mic_gain_db", 0));
        updateMediaSession();
        BoxProtocolSession current = session;
        if (current == null) return;
        // 连接模式固定自动；麦克风固定使用汽车麦克风；驾驶方向固定左舵。
        current.setAutoConnectEnabled(true);
        current.setNightMode(currentNightSetting());
        current.setMicSource(0);
        current.setAutoPlayMusic(prefs.getBoolean("auto_play_music", false));
        current.setRebootAfterUpgrade(prefs.getBoolean("reboot_after_upgrade", true));
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

    private void setPhoneStatus(String status) {
        phoneStatus = status;
        notifyClient();
    }

    private final java.util.ArrayDeque<String> navigationLog = new java.util.ArrayDeque<>();
    private final NavigationText navigationText = new NavigationText();

    String navigationText() {
        return navigationText.text();
    }

    private void clearNavigationState(String message) {
        navigationText.clear(message);
    }

    private void updateNavigationText(org.json.JSONObject data) {
        try {
            navigationText.update(data);
        } catch (org.json.JSONException exception) {
            Log.e(TAG, "导航文字字段解析失败", exception);
            recordNavigationData("导航文字字段解析失败：" + exception.getMessage());
            return;
        }
        if (client != null) client.onNavigationLogChanged();
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
        capture.close();
        mediaSession.release();
        stopForeground(true);
        Log.i(TAG, "Connection service destroyed");
        super.onDestroy();
    }
}
