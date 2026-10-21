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
        void onConnectionState();
        void onVideoFrame(VideoFrame frame);
        void onMicrophonePermissionNeeded();
    }

    final class LocalBinder extends Binder {
        ConnectionService getService() { return ConnectionService.this; }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
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
            setPhoneStatus("手机：麦克风采集失败：" + error);
        }));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoPlay:connection");
        wakeLock.setReferenceCounted(false);
        mediaSession = MediaControls.create(this);
        refreshSettings();
        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        AndroidCompat.registerReceiver(this, usbReceiver, filter, false);
        Log.i(TAG, "Connection service created");
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    private AudioPlayback createPlayback() {
        return new AudioPlayback(error -> handler.post(
                () -> setBoxStatus("盒子：音频播放失败：" + error)));
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
        if (!BoxCore.isAvailable()) {
            legacyUsbPermissionDiagnostic = "旧系统授权不可用：libnx1 加载失败："
                    + BoxCore.getLoadError();
            Log.e(TAG, legacyUsbPermissionDiagnostic);
            return;
        }
        try {
            // Match the original app, but trust the framework's permission state, not JNI's return.
            BoxCore.stUsb(device, usbManager);
            legacyUsbPermissionDiagnostic = usbManager.hasPermission(device)
                    ? "旧系统授权成功：系统已确认 USB 权限"
                    : "旧系统授权未生效：继续申请系统弹窗";
            Log.i(TAG, legacyUsbPermissionDiagnostic);
        } catch (LinkageError | SecurityException exception) {
            legacyUsbPermissionDiagnostic = "旧系统授权失败："
                    + exception.getClass().getSimpleName() + "：" + exception.getMessage();
            Log.e(TAG, legacyUsbPermissionDiagnostic, exception);
        }
    }

    private boolean hasUsbManagementPermission() {
        return checkPermission(MANAGE_USB, android.os.Process.myPid(), android.os.Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }

    String usbDiagnostics() {
        StringBuilder text = new StringBuilder("Android ")
                .append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT)
                .append("\n应用前台：").append(clientVisible ? "是" : "否")
                .append("\n选择通道：Android USB")
                .append("\n当前通道：").append(connection == null ? "未连接" : connection.name())
                .append("\n系统应用：").append((getApplicationInfo().flags
                        & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                        ? "是" : "否")
                .append("\nMANAGE_USB：").append(hasUsbManagementPermission() ? "已授予" : "未授予")
                .append("\n").append(legacyUsbPermissionDiagnostic)
                .append("\n普通授权：").append(usbPermissionDiagnostic);
        java.util.Map<String, UsbDevice> devices = usbManager.getDeviceList();
        text.append("\nUSB 设备数：").append(devices.size());
        for (UsbDevice device : devices.values()) {
            text.append("\nVID/PID：").append(Integer.toHexString(device.getVendorId()))
                    .append("/").append(Integer.toHexString(device.getProductId()))
                    .append(isTarget(device) ? "（车机盒）" : "（非目标设备）")
                    .append("；权限：").append(usbManager.hasPermission(device) ? "已允许" : "未允许");
        }
        return text.toString();
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
            connection = UsbConnection.open(usbManager, device);
            deviceName = device.getDeviceName();
            playback.close();
            playback = createPlayback();
            updateBackgroundPolicy();
            setBoxStatus("盒子：" + connection.name() + " 通道已建立");
            setPhoneStatus("手机：等待盒子状态");
            startProtocol();
        } catch (IOException exception) {
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
        int epoch = ++generation;
        AudioPlayback sessionPlayback = playback;
        session = new BoxProtocolSession(connection, width, height,
                VideoPreferences.frameRate(prefs()), new BoxProtocolSession.Listener() {
            @Override public void onStatus(String status) {
                dispatch(epoch, () -> setBoxStatus("盒子：" + status));
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
                    phonePlugged = plugged;
                    bluetoothLinked = bluetooth;
                    wifiLinked = wifi;
                    if (!plugged) {
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
        syncCarLogo();
        notifyClient();
    }

    void syncCarLogo() {
        BoxProtocolSession current = session;
        if (current == null) return;
        String path = prefs().getString("CarPlayOEMIconPath", "CarLogo/default.png");
        new Thread(() -> {
            try {
                CarLogoUpload.send(this, current, path);
            } catch (IOException | org.json.JSONException exception) {
                Log.e(TAG, "Cannot upload car logo", exception);
                handler.post(() -> {
                    if (session == current) setBoxStatus("盒子：读取汽车图标失败：" + exception.getMessage());
                });
            }
        }, "autoplay-car-logo-prepare").start();
    }

    void refreshSettings() {
        SharedPreferences prefs = prefs();
        playback.setOutputGain(prefs.getInt("audio_output_gain", 100) / 100f);
        playback.setNavigationMediaGain(prefs.getInt("navigation_media_gain", 100) / 100f);
        capture.setGainDb(prefs.getInt("mic_gain_db", 0));
        updateMediaSession();
        BoxProtocolSession current = session;
        if (current == null) return;
        current.setAutoConnectEnabled(prefs.getInt("connect_mode", 0) == 0);
        current.setNightMode(currentNightSetting());
        current.setMicSource(prefs.getInt("mic_type", 0));
        current.setAutoPlayMusic(prefs.getBoolean("auto_play_music", false));
        List<BoxSetting> settings = new ArrayList<>();
        if (prefs.contains("drive_side")) settings.add(BoxSetting.integer("drivePosition", prefs.getInt("drive_side", 0)));
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
        return mode == 0 ? 1 : mode == 1 ? 0 : -1;
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
        return current != null && current.sendMediaCommand(command);
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
        if (!sendMediaCommand(command)) {
            Log.w("AutoPlayKeys", "Steering command rejected: " + source + " action=" + command);
            return false;
        }
        lastSteeringCommand = canonical;
        lastSteeringSource = source;
        lastSteeringTime = now;
        return true;
    }

    int mediaCommand(int keyCode) {
        int learned = SteeringProtocol.androidCommand(prefs(), keyCode);
        if (learned >= 0) return learned;
        if (prefs().getBoolean("volume_track", false)) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return 205;
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return 204;
        }
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

    private void notifyClient() {
        updateMediaSession();
        if (client != null) client.onConnectionState();
    }

    private void closeConnection() {
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
        boxStatus = "盒子：未连接";
        phoneStatus = "手机：未连接";
        notifyClient();
    }

    @Override public void onDestroy() {
        destroyed = true;
        client = null;
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
