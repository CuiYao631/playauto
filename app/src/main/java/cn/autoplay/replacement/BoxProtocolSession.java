package cn.autoplay.replacement;

import android.os.SystemClock;

import cn.manstep.phonemirrorBox.BoxInterface.BoxCore;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class BoxProtocolSession implements AutoCloseable {
    private static final int CMD_OPEN = 1;
    private static final int CMD_PLUG = 2;
    private static final int CMD_PHASE = 3;
    private static final int CMD_PLUG_OUT = 4;
    private static final int CMD_VIDEO_FRAME = 6;
    private static final int CMD_CARPLAY = 8;
    private static final int CMD_AUTO_CONNECT = 8;
    private static final int CMD_SET_CAR_LOGO_TYPE = 9;
    private static final int CMD_SET_CAR_BRAND = 20;
    private static final int CMD_UPLOAD_FILE = 153;
    private static final int CARPLAY_REQUEST_KEY_FRAME = 12;
    // Same per-transfer limit as the original app; larger bulk writes fail on many hosts.
    private static final int USB_WRITE_CHUNK_SIZE = 16384;
    private static final int CMD_BLUETOOTH_PAIR_LIST = 18;
    private static final int CMD_ENABLE_CRYPT = 240;
    private static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;
    private static final int READ_TIMEOUT_MS = 250;
    // The original app re-sends the open command every second until the box answers.
    private static final int OPEN_RETRY_INTERVAL_MS = 1000;
    private static final int MAX_OPEN_RETRIES = 12;
    private static final long HEARTBEAT_INTERVAL_MS = 2000L;
    // Short grace period so the box can reconnect a paired phone on its own first.
    private static final long AUTO_CONNECT_DELAY_MS = 2000L;

    public interface Listener {
        default void onControlDiagnostic(String message) { }
        default void onError(String message, Throwable error) { }
        default void onNavigationData(String data) { }
        default void onHostUiRequested() { }
        void onStatus(String status);

        void onPhoneStatus(String status);
        void onVideoFrame(VideoFrame frame);
        void onAudioFrame(AudioFrame frame);
        void onAudioStreamStopped(int decodeType, int audioType);
        void onMicrophoneStart(int decodeType);
        void onMicrophoneStop();

        default void onLinkState(boolean phonePlugged, boolean bluetoothConnected, boolean wifiConnected) {
        }

        default void onBoxVersion(String version) {
        }

        default void onSessionEnded() {
        }
    }

    private final UsbTransport connection;
    private int width;
    private int height;
    private int frameRate;
    private volatile int activeBoxVersionCode = BoxOpenRequest.DEFAULT_BOX_VERSION_CODE;
    private final Listener listener;
    private volatile boolean closed;
    private volatile boolean cryptEnabled;
    private volatile boolean phoneWorking;
    private volatile boolean bluetoothConnected;
    private volatile boolean wifiConnected;
    private volatile boolean phonePlugged;
    private volatile boolean autoConnectEnabled = true;
    private volatile int nightModeSetting = -1;
    private volatile int micSourceSetting;
    private volatile boolean autoConnectRequested;
    private volatile boolean protocolReady;
    private volatile boolean heartbeatStarted;
    private boolean boxSupportsAutoConnect;
    private int pairedDeviceCount;
    private int phoneType = -2;
    private int phonePhase = -1;
    private int microphoneDecodeType = -1;
    private boolean microphoneRequested;
    private Thread worker;
    private int nonce;
    private volatile CarLogoSelection selectedCarLogo;
    private volatile List<BoxSetting> boxSettings = Collections.emptyList();
    private volatile boolean autoPlayMusic;
    private boolean autoPlaySent;
    private final ThreadPoolExecutor touchExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16),
            runnable -> {
                Thread thread = new Thread(runnable, "autoplay-touch-sender");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ThreadPoolExecutor controlExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
            runnable -> new Thread(runnable, "autoplay-control-sender"),
            new ThreadPoolExecutor.AbortPolicy());

    public BoxProtocolSession(
            UsbTransport connection,
            int width,
            int height,
            int frameRate,
            Listener listener) {
        this.connection = connection;
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.listener = listener;
    }

    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(this::runSession, "autoplay-box-protocol");
        worker.start();
    }

    synchronized void setDisplayParameters(int width, int height, int frameRate) {
        BoxOpenRequest.createPayload(width, height, frameRate, activeBoxVersionCode);
        if (this.width == width && this.height == height && this.frameRate == frameRate) return;
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        if (protocolReady && !closed) {
            runOnControlExecutor(() -> sendOpen(cryptEnabled, activeBoxVersionCode));
        }
    }

    public void requestKeyFrame() {
        if (!protocolReady || closed) {
            return;
        }
        byte[] payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CARPLAY_REQUEST_KEY_FRAME).array();
        try {
            touchExecutor.execute(() -> {
                try {
                    writeFrame(ProtocolFrame.encode(CMD_CARPLAY, payload, cryptEnabled, nonce));
                } catch (IOException ignored) {
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    public void setCarLogo(String iconName, String displayName, int brandId,
            boolean showOemLabel, byte[] icon120, byte[] icon180, byte[] icon256) {
        if (iconName == null || displayName == null || isEmpty(icon120) || isEmpty(icon180)
                || isEmpty(icon256)) {
            return;
        }
        selectedCarLogo = new CarLogoSelection(iconName, displayName, brandId, showOemLabel,
                icon120.clone(), icon180.clone(), icon256.clone());
        if (protocolReady && !closed) {
            uploadSelectedCarLogo();
        }
    }

    private static boolean isEmpty(byte[] data) {
        return data == null || data.length == 0;
    }

    public void setAutoConnectEnabled(boolean enabled) {
        autoConnectEnabled = enabled;
        if (enabled) {
            maybeAutoConnect();
        }
    }

    /** -1 leaves day/night to the phone, 0 forces day, 1 forces night. */
    public void setNightMode(int setting) {
        nightModeSetting = setting;
        if (protocolReady && !closed) {
            sendNightMode();
        }
    }

    /** 0 car microphone, 1 phone microphone, 2 box microphone. */
    public void setMicSource(int source) {
        micSourceSetting = source;
        if (protocolReady && !closed) {
            sendMicSource();
        }
    }

    private void applyRuntimeConfig() {
        sendMicSource();
        sendNightMode();
        sendBoxSettings();
    }

    void setBoxSettings(List<BoxSetting> settings) {
        boxSettings = Collections.unmodifiableList(new ArrayList<>(settings));
        if (protocolReady && !closed) {
            sendBoxSettings();
        }
    }

    private void sendBoxSettings() {
        List<BoxSetting> settings = boxSettings;
        runOnControlExecutor(() -> {
            for (BoxSetting setting : settings) {
                writeFrame(ProtocolFrame.encode(setting.command, setting.payload(), cryptEnabled, nonce));
            }
        });
    }

    void setAutoPlayMusic(boolean enabled) {
        autoPlayMusic = enabled;
    }

    boolean sendMediaCommand(int command) {
        int[] commands = steeringCommands(command, callState);
        if (!protocolReady || !phonePlugged || closed) {
            android.util.Log.w("AutoPlayKeys", "command_rejected button="
                    + SettingsScreens.steeringActionName(command) + " phoneConnected=" + phonePlugged
                    + " protocolReady=" + protocolReady + " closed=" + closed);
            return false;
        }
        android.util.Log.i("AutoPlayKeys", "command_queued button="
                + SettingsScreens.steeringActionName(command) + " callState=" + callState
                + " wireCommands=" + java.util.Arrays.toString(commands));
        controlDiagnostic("方控动作=" + command + "，电话状态=" + callState
                + "，排队命令=" + java.util.Arrays.toString(commands));
        for (int value : commands) sendCarPlayCommand(value);
        return true;
    }

    // Local action IDs never go on the wire. Audio controls 14/4/5 report incoming/active/ended calls.
    static int[] steeringCommands(int action, int callState) {
        if (action == 10004) return callState != 0 ? new int[]{301} : new int[]{205};
        if (action == 10005) return callState != 0 ? new int[]{300} : new int[]{204};
        if (action == 10001) return callState != 0 ? new int[]{300} : new int[]{205};
        if (action == 10002) return callState != 0 ? new int[]{301} : new int[]{204};
        if (action == 10003) return new int[]{5, 6};
        if (action == 106 || action >= 200 && action <= 205) return new int[]{action};
        throw new IllegalArgumentException("Invalid media command: " + action);
    }

    private volatile int callState;

    private void controlDiagnostic(String message) {
        if (listener != null) listener.onControlDiagnostic(message);
    }

    private void sendMicSource() {
        int source = micSourceSetting;
        sendCarPlayCommand(source == 1 ? 15 : source == 2 ? 21 : 7);
    }

    private void sendNightMode() {
        int night = nightModeSetting;
        if (night < 0) {
            return;
        }
        sendCarPlayCommand(night == 1 ? 16 : 17);
        byte[] flag = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(night).array();
        runOnControlExecutor(() -> uploadFile("/tmp/night_mode\0", flag));
    }

    private void sendCarPlayCommand(int value) {
        byte[] payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
        runOnControlExecutor(() -> {
            writeFrame(ProtocolFrame.encode(CMD_CARPLAY, payload, cryptEnabled, nonce));
            if (value == 300 || value == 301) {
                controlDiagnostic("已写入 USB：CMD 8 / " + value
                        + "（不代表手机已执行）");
            }
        });
    }

    private interface IoTask {
        void run() throws IOException;
    }

    private void runOnControlExecutor(IoTask task) {
        try {
            controlExecutor.execute(() -> {
                try {
                    task.run();
                } catch (IOException exception) {
                    if (!closed) {
                        if (listener != null) listener.onError("设置或控制命令发送失败", exception);
                        publish("设置或控制命令发送失败：" + exception.getMessage());
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            if (!closed) {
                publish("设置或控制命令队列已停止");
            }
        }
    }

    private void publishLinkState() {
        if (!phonePlugged) {
            autoPlaySent = false;
        } else if (phoneWorking && autoPlayMusic && !autoPlaySent) {
            autoPlaySent = true;
            sendMediaCommand(201);
        }
        if (listener != null) {
            listener.onLinkState(phonePlugged, bluetoothConnected, wifiConnected);
        }
    }

    private void runSession() {
        try {
            nonce = nextNonce();
            boolean boxVersionFallbackSent = false;
            int boxVersionCode = BoxOpenRequest.DEFAULT_BOX_VERSION_CODE;
            String boxSoftwareVersion = null;
            sendEnableCrypt();
            sendOpen(false, boxVersionCode);
            publish("已发送 CarPlay 协议握手，等待车机盒响应");

            int retries = 0;
            boolean openResponseReceived = false;
            long negotiationDeadline = 0;
            while (!closed) {
                Packet packet;
                try {
                    packet = readPacket();
                } catch (SocketTimeoutException timeout) {
                    if (openResponseReceived) {
                        if (SystemClock.elapsedRealtime() >= negotiationDeadline) {
                            publish("已收到 Legacy CMD_OPEN：" + width + "x" + height
                                    + "，盒子软件 " + (boxSoftwareVersion == null ? "未知" : boxSoftwareVersion)
                                    + "，尚未收到加密确认；继续监听盒子状态");
                            negotiationDeadline = Long.MAX_VALUE;
                        }
                        continue;
                    }
                    if (protocolReady) {
                        continue;
                    }
                    if (retries++ >= MAX_OPEN_RETRIES) {
                        publish("USB 通道正常，但车机盒未响应协议握手");
                        return;
                    }
                    sendOpen(cryptEnabled, boxVersionCode);
                    continue;
                }

                if (packet.command == CMD_ENABLE_CRYPT) {
                    cryptEnabled = true;
                    if (!BoxCore.isAvailable()) {
                        publish("车机盒要求加密，但 libnx1 JNI bridge 未加载");
                        return;
                    }
                    openResponseReceived = false;
                    retries = 0;
                    sendOpen(true, boxVersionCode);
                    publish("已协商加密，正在等待 CMD_OPEN 确认");
                } else if (packet.command == CMD_OPEN) {
                    if (packet.payload.length != BoxOpenRequest.PAYLOAD_SIZE) {
                        publish("收到长度异常的 CMD_OPEN 响应：" + packet.payload.length + " 字节");
                        continue;
                    }
                    ByteBuffer open = ByteBuffer.wrap(packet.payload).order(ByteOrder.LITTLE_ENDIAN);
                    int boxWidth = open.getInt(0);
                    int boxHeight = open.getInt(4);
                    int frameRate = open.getInt(8);
                    int boxVersion = open.getInt(20);
                    if (boxWidth <= 0 || boxWidth > 4000 || boxHeight <= 0 || boxHeight > 4000
                            || frameRate <= 0 || frameRate > 60) {
                        publish("车机盒返回的显示参数无效");
                        continue;
                    }
                    if (boxVersion != 0) activeBoxVersionCode = boxVersion;
                    if (boxVersion == 0) {
                        openResponseReceived = true;
                        negotiationDeadline = SystemClock.elapsedRealtime() + 10000;
                        publish("盒子尚未返回有效版本码，等待盒子固件版本");
                        continue;
                    } else if (cryptEnabled) {
                        activeBoxVersionCode = boxVersion;
                        protocolReady = true;
                        startHeartbeat();
                        uploadSelectedCarLogo();
                        applyRuntimeConfig();
                        publish("协议握手完成：" + boxWidth + "x" + boxHeight + " @ " + frameRate
                            + " fps，版本码 " + boxVersion + "；等待 H.264 视频帧");
                        maybeAutoConnect();
                    } else {
                        protocolReady = true;
                        startHeartbeat();
                        uploadSelectedCarLogo();
                        applyRuntimeConfig();
                        openResponseReceived = true;
                        negotiationDeadline = SystemClock.elapsedRealtime() + 10000;
                        publish("已收到 Legacy CMD_OPEN：" + boxWidth + "x" + boxHeight
                                + "，版本码 " + boxVersion + "；继续监听连接状态");
                        maybeAutoConnect();
                    }
                } else if (packet.command == 204 && packet.payload.length >= 18) {
                    boxSoftwareVersion = new String(
                            packet.payload, 0, 18, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
                    publish("车机盒软件版本：" + boxSoftwareVersion);
                    if (listener != null) {
                        listener.onBoxVersion(boxSoftwareVersion);
                    }
                    if (!boxVersionFallbackSent) {
                        int fallbackCode = BoxOpenRequest.versionCodeFallback(boxSoftwareVersion);
                        if (fallbackCode > 0) {
                            boxVersionCode = fallbackCode;
                            activeBoxVersionCode = fallbackCode;
                            boxVersionFallbackSent = true;
                            openResponseReceived = false;
                            retries = 0;
                            sendOpen(cryptEnabled, boxVersionCode);
                            publish("根据盒子固件版本重发 CMD_OPEN，版本码 " + boxVersionCode);
                        }
                    }
                } else if (packet.command == CMD_PLUG) {
                    handlePhonePlug(packet.payload);
                } else if (packet.command == CMD_PHASE) {
                    handlePhonePhase(packet.payload);
                } else if (packet.command == CMD_PLUG_OUT) {
                    phoneWorking = false;
                    phonePhase = -1;
                    publishPhoneStatus("手机已断开");
                    autoConnectRequested = false;
                    phonePlugged = false;
                    callState = 0;
                    wifiConnected = false;
                    publishLinkState();
                } else if (packet.command == 42) {
                    handleDashboardData(packet.payload);
                } else if (packet.command == CMD_CARPLAY) {
                    handleCarPlayStatus(packet.payload);
                } else if (packet.command == CMD_BLUETOOTH_PAIR_LIST) {
                    handleBluetoothPairList(packet.payload);
                } else if (packet.command == CMD_VIDEO_FRAME && protocolReady) {
                    boolean hasTimestamp = boxSoftwareVersion != null
                            && boxSoftwareVersion.compareTo("2024.12.13.1130") >= 0;
                    VideoFrame frame = VideoFrame.parse(packet.payload, hasTimestamp);
                    if (listener != null) {
                        listener.onVideoFrame(frame);
                    }
                } else if (packet.command == 7 && protocolReady) {
                    handleAudioPacket(packet.payload);
                }
            }
        } catch (IOException | RuntimeException exception) {
            if (!closed) {
                if (listener != null) listener.onError("协议通信失败", exception);
                publish("协议通信失败：" + exception.getMessage());
            }
        } catch (LinkageError error) {
            if (!closed) {
                if (listener != null) listener.onError("原生加密桥调用失败", error);
                publish("native 加密桥调用失败：" + error.getClass().getSimpleName());
            }
        } finally {
            if (!closed && listener != null) {
                listener.onSessionEnded();
            }
        }
    }

    private void sendEnableCrypt() throws IOException {
        byte[] payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(nonce).array();
        writeFrame(ProtocolFrame.encodeLegacy(CMD_ENABLE_CRYPT, payload));
    }

    private synchronized void sendOpen(boolean encrypt, int boxVersionCode) throws IOException {
        byte[] payload = BoxOpenRequest.createPayload(width, height, frameRate, boxVersionCode);
        byte[] frame = ProtocolFrame.encode(CMD_OPEN, payload, encrypt, nonce);
        writeFrame(frame);
    }

    private void uploadSelectedCarLogo() {
        CarLogoSelection logo = selectedCarLogo;
        if (logo == null || closed || !protocolReady) {
            return;
        }
        Thread uploadThread = new Thread(() -> {
            try {
                writeFrame(ProtocolFrame.encode(CMD_SET_CAR_BRAND,
                        createCarBrandPayload(logo.brandId), cryptEnabled, nonce));
                uploadFile("/etc/icon_120x120.png\0", logo.icon120);
                uploadFile("/etc/icon_180x180.png\0", logo.icon180);
                uploadFile("/etc/icon_256x256.png\0", logo.icon256);
                writeFrame(ProtocolFrame.encode(CMD_SET_CAR_LOGO_TYPE,
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array(),
                        cryptEnabled, nonce));
                String label = logo.showOemLabel ? "oemIconLabel = " + logo.displayName + "\n" : "";
                String config = "oemIconVisible = 1\n" + label
                        + "name = AutoPlay Replacement\n"
                        + "model = Magic-Car-Link-1.00\n"
                        + "oemIconPath = /etc/oem_icon.png\n";
                uploadFile("/etc/airplay.conf\0", config.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                publish("CarPlay 汽车图标已同步到车机盒：" + logo.displayName);
            } catch (IOException exception) {
                publish("CarPlay 汽车图标同步失败：" + exception.getMessage());
            }
        }, "autoplay-car-logo-upload");
        uploadThread.setDaemon(true);
        uploadThread.start();
    }

    private void uploadFile(String boxPath, byte[] data) throws IOException {
        byte[] path = boxPath.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload = ByteBuffer.allocate(8 + path.length + data.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(path.length)
                .put(path)
                .putInt(data.length)
                .put(data)
                .array();
        writeFrame(ProtocolFrame.encode(CMD_UPLOAD_FILE, payload, cryptEnabled, nonce));
    }

    static byte[] createBoxFilePayload(String boxPath, byte[] data) {
        byte[] path = boxPath.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + path.length + data.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(path.length)
                .put(path)
                .putInt(data.length)
                .put(data)
                .array();
    }

    static byte[] createCarBrandPayload(int brandId) {
        return ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(brandId)
                .putInt(0)
                .array();
    }

    private void startHeartbeat() {
        if (heartbeatStarted || closed) {
            return;
        }
        heartbeatStarted = true;
        Thread heartbeatThread = new Thread(() -> {
            while (!closed) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!closed && protocolReady) {
                    try {
                        writeFrame(ProtocolFrame.encode(170, new byte[0], cryptEnabled, nonce));
                    } catch (IOException exception) {
                        publish("盒子心跳发送失败：" + exception.getMessage());
                        return;
                    }
                }
            }
        }, "autoplay-box-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    public void sendMicrophonePcm(int decodeType, byte[] pcmData) throws IOException {
        if (closed || !protocolReady || pcmData == null || pcmData.length == 0) {
            return;
        }
        ByteBuffer payload = ByteBuffer.allocate(12 + pcmData.length).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(decodeType);
        payload.putFloat(0.0f);
        payload.putInt(3);
        payload.put(pcmData);
        writeFrame(ProtocolFrame.encode(7, payload.array(), cryptEnabled, nonce));
    }

    public void sendTouchFrame(byte[] touchData) {
        if (closed || !protocolReady || touchData == null || touchData.length == 0
                || touchData.length > TouchFrame.POINT_SIZE * TouchFrame.MAX_POINTERS
                || touchData.length % TouchFrame.POINT_SIZE != 0) {
            return;
        }
        byte[] data = touchData.clone();
        try {
            touchExecutor.execute(() -> {
                if (closed) {
                    return;
                }
                try {
                    writeFrame(ProtocolFrame.encode(23, data, cryptEnabled, nonce));
                } catch (IOException exception) {
                    publish("触控事件发送失败：" + exception.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void handlePhonePlug(byte[] payload) throws IOException {
        if (payload.length != 4 && payload.length != 8) {
            throw new IOException("Invalid CMD_PLUG payload length: " + payload.length);
        }
        ByteBuffer data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        phoneType = data.getInt(0);
        boolean wifiFlag = payload.length >= 8 && data.getInt(4) == 1;
        phonePlugged = true;
        callState = 0;
        wifiConnected = wifiFlag;
        publishLinkState();
        publishPhoneStatus("已检测到 " + phoneTypeName(phoneType)
                + (wifiFlag ? "，Wi-Fi 已连接" : "，等待连接"));
    }

    private void handleAudioPacket(byte[] payload) throws IOException {
        if (payload.length < AudioFrame.HEADER_SIZE) {
            throw new IOException("Incomplete audio packet header");
        }
        ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int decodeType = header.getInt(0);
        int audioType = header.getInt(8);
        if (payload.length == AudioFrame.HEADER_SIZE + 4) {
            // The original protocol uses a float volume transition, not a control byte.
            return;
        }
        if (payload.length == AudioFrame.HEADER_SIZE + 1) {
            int control = payload[AudioFrame.HEADER_SIZE] & 0xFF;
            if (control == 14) callState = 2;
            else if (control == 4) callState = 1;
            else if (control == 5) callState = 0;
            if (control == 14 || control == 4 || control == 5) {
                android.util.Log.i("AutoPlayKeys", "phone_audio_control=" + control
                        + " callState=" + callState);
                controlDiagnostic("电话音频控制=" + control + "，电话状态=" + callState
                        + "，音频类型=" + audioType + "，解码类型=" + decodeType);
            }
            if (control == 2 && listener != null) {
                listener.onAudioStreamStopped(decodeType, audioType);
            } else if (control == 3) {
                microphoneDecodeType = decodeType;
                if (microphoneRequested && listener != null) {
                    listener.onMicrophoneStart(microphoneDecodeType);
                }
            }
            return;
        }
        AudioFrame frame = AudioFrame.parse(payload);
        if (listener != null) {
            listener.onAudioFrame(frame);
        }
    }

    private void handlePhonePhase(byte[] payload) throws IOException {
        if (payload.length < 4) {
            throw new IOException("Invalid CMD_PHASE payload length: " + payload.length);
        }
        int phase = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt();
        phonePhase = phase;
        switch (phase) {
            case 3:
                phoneWorking = true;
                publishPhoneStatus(phoneTypeName(phoneType) + " 工作中");
                break;
            case 4:
                phoneWorking = false;
                publishPhoneStatus("等待手机热点");
                maybeAutoConnect();
                break;
            case 5:
                phoneWorking = false;
                publishPhoneStatus("等待 AirPlay");
                maybeAutoConnect();
                break;
            case 6:
            case 8:
                phoneWorking = true;
                publishPhoneStatus(phoneTypeName(phoneType) + " 已连接并工作");
                break;
            case 11:
                publishPhoneStatus("手机正在唤醒");
                break;
            case 13:
                phoneWorking = false;
                publishPhoneStatus("手机连接失败");
                maybeAutoConnect();
                break;
            case 1001:
                publishPhoneStatus("连接失败：车机蓝牙异常");
                break;
            case 1002:
                publishPhoneStatus("连接失败：车机 Wi-Fi 异常");
                break;
            default:
                publishPhoneStatus("连接阶段：" + phase);
                break;
        }
        publishLinkState();
    }

    private void handleDashboardData(byte[] payload) {
        if (payload.length < 4) {
            android.util.Log.e("AutoPlayNavigation", "Dashboard packet shorter than subtype header");
            if (listener != null) listener.onNavigationData("数据错误：命令 42 缺少子类型头");
            return;
        }
        int subtype = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (subtype != 200) return;
        int end = payload.length;
        while (end > 4 && payload[end - 1] == 0) end--;
        if (end - 4 > 16384) {
            android.util.Log.e("AutoPlayNavigation", "Navigation payload exceeds 16 KiB");
            if (listener != null) listener.onNavigationData("数据错误：导航 JSON 超过 16 KiB，未保存");
            return;
        }
        String data = new String(payload, 4, end - 4, java.nio.charset.StandardCharsets.UTF_8);
        try {
            new org.json.JSONObject(data);
        } catch (org.json.JSONException exception) {
            android.util.Log.e("AutoPlayNavigation", "Invalid navigation JSON", exception);
            data = "JSON 解析失败：" + exception.getMessage() + "\n原始数据：" + data;
        }
        if (listener != null) listener.onNavigationData("CMD 42 / 200\n" + data);
    }

    private void handleCarPlayStatus(byte[] payload) throws IOException {
        if (payload.length < 4) {
            throw new IOException("Invalid CarPlay command payload length: " + payload.length);
        }
        int subcommand = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (subcommand == 1) {
            microphoneRequested = true;
            if (microphoneDecodeType > 0 && listener != null) {
                listener.onMicrophoneStart(microphoneDecodeType);
            } else {
                publishPhoneStatus("等待盒子协商麦克风音频格式");
            }
        } else if (subcommand == 2) {
            microphoneRequested = false;
            if (listener != null) {
                listener.onMicrophoneStop();
            }
        } else if (subcommand == 3) {
            android.util.Log.i("AutoPlay", "CarPlay_RequestHostUI");
            if (listener != null) listener.onHostUiRequested();
        } else if (subcommand == 506 || subcommand == 507) {
            if (listener != null) listener.onNavigationData("CMD 8 / " + subcommand
                    + (subcommand == 506 ? "：盒子通知导航开始" : "：盒子通知导航结束")
                    + "（独立状态消息，不是导航 JSON）");
        } else if (subcommand == 1001) {
            boxSupportsAutoConnect = true;
            publish("车机盒支持自动连接");
            maybeAutoConnect();
        } else if (subcommand == 1007) {
            bluetoothConnected = true;
            publishLinkState();
            publishPhoneStatus("车机蓝牙已连接");
        } else if (subcommand == 1008) {
            bluetoothConnected = false;
            publishLinkState();
            publishPhoneStatus("车机蓝牙已断开");
            maybeAutoConnect();
        } else if (subcommand == 1009) {
            wifiConnected = true;
            publishLinkState();
            publishPhoneStatus("车机 Wi-Fi 已连接");
        } else if (subcommand == 1010) {
            wifiConnected = false;
            publishLinkState();
            publishPhoneStatus("车机 Wi-Fi 已断开");
        }
    }

    private void handleBluetoothPairList(byte[] payload) {
        String pairList = new String(payload, java.nio.charset.StandardCharsets.UTF_8).trim();
        pairedDeviceCount = pairList.isEmpty() ? 0 : pairList.split("\\n").length;
        publishPhoneStatus(pairedDeviceCount == 0
                ? "没有已配对手机，自动连接不可用"
                : "已配对手机：" + pairedDeviceCount + " 台");
        maybeAutoConnect();
    }

    private void maybeAutoConnect() {
        if (!protocolReady || !boxSupportsAutoConnect || !autoConnectEnabled
                || pairedDeviceCount <= 0 || phoneWorking || bluetoothConnected
                || autoConnectRequested || closed) {
            return;
        }
        autoConnectRequested = true;
        Thread autoConnectThread = new Thread(() -> {
            try {
                Thread.sleep(AUTO_CONNECT_DELAY_MS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                autoConnectRequested = false;
                return;
            }
            if (!closed && autoConnectEnabled && !phoneWorking && !bluetoothConnected) {
                try {
                    byte[] payload = ByteBuffer.allocate(4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(1002)
                            .array();
                    writeFrame(ProtocolFrame.encode(
                            CMD_AUTO_CONNECT, payload, cryptEnabled, nonce));
                    publish("已在手机等待阶段请求盒子自动连接");
                } catch (IOException exception) {
                    publish("自动连接请求失败：" + exception.getMessage());
                    autoConnectRequested = false;
                }
            } else {
                autoConnectRequested = false;
            }
        }, "autoplay-auto-connect");
        autoConnectThread.start();
    }

    private static String phoneTypeName(int type) {
        switch (type) {
            case 1:
                return "Android 镜像";
            case 3:
                return "CarPlay";
            case 4:
                return "iPhone 镜像";
            case 5:
                return "Android Auto";
            case 6:
                return "HiCar";
            case 7:
                return "Carlink";
            case 8:
                return "CarLife+";
            default:
                return type == -2 ? "手机" : "未知手机类型 " + type;
        }
    }

    private Packet readPacket() throws IOException {
        byte[] headerBytes = readFully(ProtocolFrame.HEADER_SIZE, 3000,
                protocolReady ? 3000 : OPEN_RETRY_INTERVAL_MS);
        ProtocolFrame.Header header = ProtocolFrame.decodeHeader(headerBytes, 0, MAX_PAYLOAD_SIZE);
        byte[] payload = readFully(header.getPayloadLength(), 10000);
        if (header.isCryptFrame()) {
            if (!cryptEnabled) {
                throw new IOException("Received a crypt frame before crypt negotiation");
            }
            payload = ProtocolFrame.decodePayload(header, payload, nonce);
        }
        return new Packet(header.getCommand(), payload);
    }

    private byte[] readFully(int length, int timeoutMillis) throws IOException {
        return readFully(length, timeoutMillis, timeoutMillis);
    }

    // idleTimeoutMillis only applies while no byte of this read has arrived yet.
    private byte[] readFully(int length, int timeoutMillis, int idleTimeoutMillis) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        long start = SystemClock.elapsedRealtime();
        long deadline = start + timeoutMillis;
        long idleDeadline = start + idleTimeoutMillis;
        while (offset < length && !closed) {
            int count = connection.bulkRead(bytes, offset, length - offset, READ_TIMEOUT_MS);
            if (count > 0) {
                offset += count;
                continue;
            }
            long now = SystemClock.elapsedRealtime();
            if (now >= deadline || (offset == 0 && now >= idleDeadline)) {
                throw new SocketTimeoutException(count < 0
                        ? "Timed out waiting for USB bulk data"
                        : "Timed out reading protocol frame");
            }
        }
        if (closed) {
            throw new IOException("Protocol session closed");
        }
        return bytes;
    }

    private synchronized void writeFrame(byte[] frame) throws IOException {
        // The box parses the 16-byte header as its own USB transfer, as the original app sends it.
        int headerLength = Math.min(frame.length, ProtocolFrame.HEADER_SIZE);
        writeSegment(frame, 0, headerLength);
        int offset = headerLength;
        while (offset < frame.length) {
            int chunk = Math.min(frame.length - offset, USB_WRITE_CHUNK_SIZE);
            writeSegment(frame, offset, chunk);
            offset += chunk;
        }
        if (closed) {
            throw new IOException("Protocol session closed");
        }
    }

    private void writeSegment(byte[] data, int start, int length) throws IOException {
        int offset = 0;
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (offset < length && !closed) {
            int count = connection.bulkWrite(data, start + offset, length - offset, 1000);
            if (count <= 0) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw new IOException("USB bulk write timed out");
                }
                continue;
            }
            offset += count;
        }
    }

    private int nextNonce() {
        int value;
        do {
            value = new SecureRandom().nextInt(Integer.MAX_VALUE);
        } while (value <= 0);
        return value;
    }

    private void publish(String status) {
        if (listener != null) {
            listener.onStatus(status);
        }
    }

    private void publishPhoneStatus(String status) {
        if (listener != null) {
            listener.onPhoneStatus(status);
        }
    }

    @Override
    public void close() {
        closed = true;
        touchExecutor.shutdownNow();
        controlExecutor.shutdownNow();
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
        }
    }

    private static final class Packet {
        private final int command;
        private final byte[] payload;

        private Packet(int command, byte[] payload) {
            this.command = command;
            this.payload = payload;
        }
    }

    private static final class CarLogoSelection {
        final String iconName;
        final String displayName;
        final int brandId;
        final boolean showOemLabel;
        final byte[] icon120;
        final byte[] icon180;
        final byte[] icon256;

        CarLogoSelection(String iconName, String displayName, int brandId,
                boolean showOemLabel, byte[] icon120, byte[] icon180, byte[] icon256) {
            this.iconName = iconName;
            this.displayName = displayName;
            this.brandId = brandId;
            this.showOemLabel = showOemLabel;
            this.icon120 = icon120;
            this.icon180 = icon180;
            this.icon256 = icon256;
        }
    }
}
