package cn.autoplay.replacement;

import android.os.SystemClock;
import android.util.Log;

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
    private static final String TAG = "AutoPlayProtocol";
    private static final int CMD_OPEN = 1;
    private static final int CMD_PLUG = 2;
    private static final int CMD_PHASE = 3;
    private static final int CMD_PLUG_OUT = 4;
    private static final int CMD_VIDEO_FRAME = 6;
    /**
     * 副屏（仪表投射）视频帧。
     *
     * <p>与 {@link #CMD_VIDEO_FRAME} 并行的一路独立 H.264 流，头结构相同，
     * 时间戳为 4 字节毫秒。playAuto 用它驱动悬浮窗模拟的副屏。
     */
    private static final int CMD_DASHBOARD_FRAME = 44;
    /** 副屏帧头长度，与主屏一致。 */
    private static final int DASHBOARD_HEADER_SIZE = 20;
    private static final int CMD_CARPLAY = 8;
    private static final int CMD_AUTO_CONNECT = 8;
    private static final int CMD_SET_CAR_LOGO_TYPE = 9;
    private static final int CMD_SET_CAR_BRAND = 20;
    /**
     * 要求盒子断开当前连接的手机（零负载）。
     *
     * <p>原版 {@code sendManualDisconnectTemp()} 只在两个场景下发：盒子设置页打开时
     * 收到「手机插入」（把手机踢掉，避免设置在投屏中途被改），以及两块车机型号在
     * 屏幕配置变化后强制手机重连。playAuto 改为设置页里的手动入口。
     *
     * <p>与 {@link #CMD_BOX_REBOOT} 一样是零负载命令：原版 {@code oO0O00o._oOoOOO()}
     * 在 {@code len == 0} 时直接返回，既不加密也不切 magic，所以帧头必须保持明文
     * {@code 0x55AA55AA}，见 {@link #createManualDisconnectFrame()}。
     */
    private static final int CMD_MANUAL_DISCONNECT = 15;
    private static final int CMD_UPLOAD_FILE = 153;
    private static final int CARPLAY_REQUEST_KEY_FRAME = 12;
    // Same per-transfer limit as the original app; larger bulk writes fail on many hosts.
    private static final int USB_WRITE_CHUNK_SIZE = 16384;
    private static final int CMD_BLUETOOTH_PAIR_LIST = 18;
    /**
     * 指定自动回连的蓝牙 MAC（17 字节 ASCII，形如 {@code AA:BB:CC:DD:EE:FF}）。
     *
     * <p>与 {@code 8 / 1002} 的区别：1002 让盒子在已配对列表里自己挑，
     * 17 则由主机点名某一台手机，多机配对时用它保证连上想要的那台。
     */
    private static final int CMD_AUTO_CONN_BT_ADDR = 17;
    private static final int CMD_ENABLE_CRYPT = 240;
    /** 盒子固件升级进度（4 字节 int，0~100）。 */
    private static final int CMD_UPDATE_PROGRESS = 177;
    /** 盒子固件升级状态（4 字节 int）。 */
    private static final int CMD_UPDATE = 187;
    /** 盒子信息上报（JSON），含 uuid / MFD / productType / fileName。 */
    private static final int CMD_BOX_INFO = 25;
    /**
     * 要求盒子重启（零负载）。
     *
     * <p>原版 {@code o__oOo0oO0_oO0_()} 只在读帧出错时用它做恢复；这里额外用于
     * 固件升级成功后主动重启。原版该类命令依赖 {@code len == 0} 提前返回，
     * 因此帧头始终是明文 magic {@code 0x55AA55AA}，见 {@link #createRebootFrame()}。
     */
    private static final int CMD_BOX_REBOOT = 206;
    /** 盒子固件升级包在盒子侧的落盘路径。 */
    public static final String FIRMWARE_BOX_PATH = "/tmp/Auto_Box_Update.img\0";
    /** 与盒子固件升级包大小上限一致（50MB）。 */
    public static final int MAX_FIRMWARE_SIZE = 50 * 1024 * 1024;
    private static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;
    private static final int READ_TIMEOUT_MS = 250;
    // The original app re-sends the open command every second until the box answers.
    private static final int OPEN_RETRY_INTERVAL_MS = 1000;
    private static final int MAX_OPEN_RETRIES = 12;
    private static final long HEARTBEAT_INTERVAL_MS = 2000L;
    /** 连续多少次心跳写失败才判定链路断开（单次超时通常只是 USB 忙）。 */
    private static final int MAX_HEARTBEAT_FAILURES = 3;
    // Short grace period so the box can reconnect a paired phone on its own first.
    private static final long AUTO_CONNECT_DELAY_MS = 2000L;
    /**
     * 手机阶段转变时补发 {@code naviScreenInfo} 的最小间隔。
     *
     * <p>盒子拿不到副屏尺寸就不会下发 {@code CMD_DASHBOARD_FRAME}(44)；
     * 5 秒内已经报过就不再重复，避免盒子反复上报同一阶段时刷帧。
     */
    private static final long NAVI_SCREEN_INFO_REFRESH_MS = 5000L;
    /**
     * 单次 bulk 写超时。原版固定用 4000 ms（{@code bulkTransfer(..., 4000)}）。
     *
     * <p>盒子在写 /etc 配置、重启 CarPlay 服务、固件升级期间会短暂停止读 USB，
     * 1 秒的等待不足以覆盖这种停顿，会误报「USB bulk write timed out」并打断会话。
     */
    private static final int USB_WRITE_TIMEOUT_MS = 4000;
    /** 汽车图标同步失败后最多重试一次（盒子忙时第二次通常就能成功）。 */
    private static final int CAR_LOGO_UPLOAD_ATTEMPTS = 2;
    private static final long CAR_LOGO_RETRY_DELAY_MS = 800L;
    /**
     * 升级成功后等待多久再下发重启。留出时间让盒子把 187 状态与 177 进度发完，
     * 否则重启指令可能抢在成功回报之前把 USB 掐断。
     */
    private static final long UPGRADE_REBOOT_DELAY_MS = 1500L;
    /**
     * 手动断开后抑制自动回连的时长。
     *
     * <p>盒子被踢掉手机后会进入等待阶段并触发 {@link #maybeAutoConnect()}，
     * 若不抑制就会在 2 秒后自己又连回来，手动断开等于白做。
     */
    static final long MANUAL_DISCONNECT_COOLDOWN_MS = 60_000L;

    /**
     * 盒子 {@code CMD_BLUETOOTH_PAIR_LIST}(18) 里的一条已配对手机。
     *
     * <p>原始行格式为「17 字符 MAC + 设备名」，见 {@link #parseBluetoothPairList(String)}。
     */
    public static final class PairedDevice {
        private final String mac;
        private final String name;

        PairedDevice(String mac, String name) {
            this.mac = mac;
            this.name = name;
        }

        public String getMac() {
            return mac;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name == null || name.isEmpty() ? mac : name + "（" + mac + "）";
        }
    }

    public interface Listener {
        default void onBoxName(boolean bluetooth, String name) { }

        default void onError(String message, Throwable error) { }
        default void onNavigationData(String data) { }
        default void onNavigationUpdate(org.json.JSONObject data) { }
        default void onNavigationEnded() { }

        /** 盒子通知导航开始（{@code CMD_CARPLAY_CMD 8 / 506}）。 */
        default void onNavigationStarted() { }

        /**
         * 媒体/歌词增量包（{@code CMD_DASHBOARD_DATA 42} 子类型 {@code 1}）。
         *
         * <p>只包含发生变化的字段，接收方必须做字段级合并，详见 {@code MediaText}。
         */
        default void onMediaUpdate(org.json.JSONObject data) { }
        default void onHostUiRequested() { }
        void onStatus(String status);

        void onPhoneStatus(String status);
        void onVideoFrame(VideoFrame frame);

        /**
         * 副屏（仪表投射）视频帧，对应 {@code CMD_DASHBOARD_FRAME}(44)。
         *
         * <p>与主屏 {@link #onVideoFrame} 是两路独立的 H.264 流，尺寸和帧率都可能不同。
         */
        default void onDashboardFrame(VideoFrame frame) {
        }

        void onAudioFrame(AudioFrame frame);

        void onAudioStreamStopped(int decodeType, int audioType);
        void onMicrophoneStart(int decodeType);
        void onMicrophoneStop();

        default void onLinkState(boolean phonePlugged, boolean bluetoothConnected, boolean wifiConnected) {
        }

        default void onBoxVersion(String version) {
        }

        /** 盒子上报的已配对手机列表（CMD 18），mac 为 {@code AA:BB:CC:DD:EE:FF} 形式。 */
        default void onPairedDevices(List<PairedDevice> devices) {
        }

        /** 盒子固件升级进度，0~100。 */
        default void onFirmwareProgress(int percent) {
        }

        /** 盒子固件升级状态，对应 CMD_UPDATE：1 开始、2 成功、3 失败、5/6/7 OTA。 */
        default void onFirmwareStatus(int status) {
        }

        /**
         * 每收到一条盒子→主机的命令都会回调一次，用于「盒子指令日志」窗口。
         *
         * <p>高频帧（6 视频 / 7 音频 / 44 副屏）由调用方自行聚合计次，
         * 协议层不做节流，避免漏掉关键控制命令。
         *
         * @param command       命令号
         * @param payloadLength 负载字节数（不含 16 字节帧头）
         * @param value         有意义的首个整数值（cmd 3 阶段 / cmd 8 子命令 /
         *                      cmd 177、187 状态 / cmd 2 手机类型），无意义时为 {@code -1}
         */
        default void onBoxCommand(int command, int payloadLength, int value) {
        }

        /**
         * 盒子信息上报（CMD_BOX_INFO 25），用于固件检查请求里的 uuid / mfd / model / fwn。
         *
         * @param firmwareName 盒子建议的固件文件名，已按 productType 推导
         */
        default void onBoxInfo(String uuid, String manufacturer, String productType,
                               String firmwareName) {
        }

        default void onSessionEnded() {
        }

        /**
         * 非致命问题（单条命令发送失败、触控/图标/配对等辅助操作失败）。
         *
         * <p>这类问题不会中断 USB 会话，视频与心跳仍可正常工作，因此不能当作
         * 「连接失败」展示，只在连接详情里留档，避免误导用户去拔插盒子。
         */
        default void onDiagnostic(String message) {
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
    private volatile List<PairedDevice> pairedDevices = Collections.emptyList();
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
    /** 固件升级成功后是否主动下发重启（cmd 206）。 */
    private volatile boolean rebootAfterUpgrade = true;
    /** 本会话是否已经安排过重启，避免重复下发。 */
    private boolean rebootRequested;
    /** 手动断开后的自动回连抑制截止时刻（{@link SystemClock#elapsedRealtime()}）。 */
    private volatile long autoConnectSuppressedUntil;
    /**
     * 最近一次要上报的副屏尺寸 {@code {width, height, fps, dpi}}。
     *
     * <p>悬浮窗在 USB 握手之前就已创建，此时 {@code protocolReady} 为 false，
     * 直接丢弃会让盒子永远收不到 {@code naviScreenInfo}，也就不会下发
     * {@code CMD_DASHBOARD_FRAME}(44)，悬浮窗只能停在「等待盒子画面」。
     */
    private volatile int[] pendingNaviScreenInfo;
    /** 上次成功写入副屏尺寸的时间，用于阶段转变时的补发限速。 */
    private volatile long lastNaviScreenInfoSentAt;
    /** 是否已经收到过副屏画面（只用于首帧日志，避免每帧刷 logcat）。 */
    private volatile boolean dashboardFrameSeen;
    /** 各类 cmd 42 子类型的上次日志时间（只用于日志限速）。 */
    private final java.util.HashMap<Integer, Long> dashboardSubtypeLogAt =
            new java.util.HashMap<>();
    private volatile String boxUuid = "";
    private volatile String boxManufacturer = "";
    private volatile String boxProductType = "";
    private volatile String boxFirmwareName = "";
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
        // 握手前创建的副屏窗口尺寸在这时补发，盒子据此才会启动副屏视频流
        flushNaviScreenInfo();
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
                try {
                    writeFrame(ProtocolFrame.encode(setting.command, setting.payload(), cryptEnabled, nonce));
                } catch (IOException exception) {
                    if (setting.command == 25 && listener != null) {
                        listener.onNavigationData("CMD 25 配置写入失败："
                                + new String(setting.payload(), java.nio.charset.StandardCharsets.UTF_8)
                                + "\n" + exception.getMessage());
                    }
                    throw exception;
                }
                if (setting.command == 25 && listener != null) {
                    listener.onNavigationData("CMD 25 配置已写入 USB："
                            + new String(setting.payload(), java.nio.charset.StandardCharsets.UTF_8)
                            + "\n仅确认本地写入完成，不代表盒子确认；等待 CMD 42 / 200 导航 JSON。");
                }
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
        Log.i("AutoPlayKeys", "方控动作=" + command + "，电话状态=" + callState
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
                Log.i("AutoPlayKeys", "已写入 USB：CMD 8 / " + value
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
                        publishDiagnostic("设置或控制命令发送失败：" + exception.getMessage());
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            if (!closed) {
                publishDiagnostic("设置或控制命令队列已停止");
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
            boolean cryptFallbackSent = false;
            int boxVersionCode = BoxOpenRequest.DEFAULT_BOX_VERSION_CODE;
            String boxSoftwareVersion = null;
            if (cryptCapable()) {
                sendEnableCrypt();
            } else {
                // 不能加密却先要求盒子切加密模式，会让盒子用本机读不了的
                // 0x55BB55BB 帧回包，握手必然失败。这种情况直接走明文（旧固件兼容路径）。
                publishDiagnostic("加密库不可用，按未加密方式握手");
            }
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

                if (listener != null) {
                    listener.onBoxCommand(packet.command, packet.payload.length,
                            commandValue(packet.command, packet.payload));
                }

                if (packet.command == CMD_ENABLE_CRYPT) {
                    if (!BoxCore.isAvailable()) {
                        publish("车机盒要求加密，但 libnx1 JNI bridge 未加载");
                        return;
                    }
                    if (!CryptoSupport.awaitUsable(3000)) {
                        // 该库在重签名构建里首次调用会写空指针直接杀进程
                        // （tombstone: SIGSEGV @ libnx1 stCheck+548 → 0xa84），
                        // 因此只能在独立进程里探测，这里绝不调用 native。
                        if (!cryptFallbackSent) {
                            cryptFallbackSent = true;
                            publish("车机盒要求加密，但原生加密库在当前应用签名下不可用，改为未加密连接");
                            sendOpen(false, boxVersionCode);
                        }
                        continue;
                    }
                    cryptEnabled = true;
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
                } else if (packet.command == 13 || packet.command == 14) {
                    try {
                        String name = BoxName.decode(packet.payload);
                        if (listener != null) listener.onBoxName(packet.command == 13, name);
                    } catch (IOException exception) {
                        if (listener != null) listener.onError("读取盒子"
                                + (packet.command == 13 ? "蓝牙" : "Wi-Fi") + "名称失败", exception);
                    }
                } else if (packet.command == 42) {
                    handleDashboardData(packet.payload);
                } else if (packet.command == CMD_CARPLAY) {
                    handleCarPlayStatus(packet.payload);
                } else if (packet.command == CMD_BLUETOOTH_PAIR_LIST) {
                    handleBluetoothPairList(packet.payload);
                } else if (packet.command == CMD_UPDATE_PROGRESS) {
                    if (packet.payload.length >= 4) {
                        int percent = ByteBuffer.wrap(packet.payload)
                                .order(ByteOrder.LITTLE_ENDIAN).getInt(0);
                        if (percent >= 0 && percent <= 100 && listener != null) {
                            listener.onFirmwareProgress(percent);
                        }
                    }
                } else if (packet.command == CMD_UPDATE) {
                    if (packet.payload.length >= 4) {
                        int status = ByteBuffer.wrap(packet.payload)
                                .order(ByteOrder.LITTLE_ENDIAN).getInt(0);
                        publish("盒子固件升级状态：" + describeFirmwareStatus(status));
                        if (listener != null) {
                            listener.onFirmwareStatus(status);
                        }
                        if (shouldRebootAfterUpgrade(status)) {
                            scheduleRebootAfterUpgrade();
                        }
                    }
                } else if (packet.command == CMD_BOX_INFO) {
                    handleBoxInfo(packet.payload);
                } else if (packet.command == CMD_VIDEO_FRAME && protocolReady) {
                    boolean hasTimestamp = boxSoftwareVersion != null
                            && boxSoftwareVersion.compareTo("2024.12.13.1130") >= 0;
                    VideoFrame frame = VideoFrame.parse(packet.payload, hasTimestamp);
                    if (listener != null) {
                        listener.onVideoFrame(frame);
                    }
                } else if (packet.command == CMD_DASHBOARD_FRAME && protocolReady) {
                    handleDashboardFrame(packet.payload);
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

    /** 本机能否真正加解密：JNI 加载成功且独立进程探针确认可用。 */
    private static boolean cryptCapable() {
        if (!BoxCore.isAvailable()) return false;
        // 探针在服务启动时就已发起（超时 4 秒），这里等它给出结论，
        // 宁可晚一点握手，也不要在一台本来能加密的机器上误判成不可用。
        return CryptoSupport.awaitUsable(5000);
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
                publishDiagnostic("CarPlay 汽车图标同步失败：" + exception.getMessage());
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

    static String describeFirmwareStatus(int status) {
        switch (status) {
            case 1: return "开始升级";
            case 2: return "升级成功";
            case 3: return "升级失败";
            case 5: return "OTA 开始";
            case 6: return "OTA 成功";
            case 7: return "OTA 失败";
            default: return "未知状态 " + status;
        }
    }

    /**
     * 解析 {@code CMD_BOX_INFO}(25) 上报的 JSON。
     *
     * <p>字段名与原版一致：{@code uuid}、{@code MFD}（厂商）、{@code productType}（型号）、
     * {@code fileName}（固件包名）。{@code fileName} 缺失时按 {@code productType} 推导。
     */
    private void handleBoxInfo(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return;
        }
        try {
            String text = new String(payload, 0, payload.length,
                    java.nio.charset.StandardCharsets.UTF_8);
            int nul = text.indexOf('\0');
            if (nul >= 0) text = text.substring(0, nul);
            org.json.JSONObject json = new org.json.JSONObject(text);

            String uuid = json.optString("uuid", "");
            String manufacturer = json.optString("MFD", "");
            String productType = json.optString("productType", "");
            String firmwareName = json.optString("fileName", "");
            if (firmwareName.isEmpty()) {
                if ("Auto_Box".equals(productType)) {
                    firmwareName = "Auto_Box_Update.img";
                } else if ("A15HW".equals(productType)) {
                    firmwareName = "A15H_Update.img";
                }
            }

            boxUuid = uuid;
            boxManufacturer = manufacturer;
            boxProductType = productType;
            boxFirmwareName = firmwareName;

            publish("盒子信息：uuid=" + uuid + "，厂商=" + manufacturer
                    + "，型号=" + productType + "，固件包=" + firmwareName);
            if (listener != null) {
                listener.onBoxInfo(uuid, manufacturer, productType, firmwareName);
            }
        } catch (org.json.JSONException exception) {
            Log.w("BoxProtocolSession", "CMD_BOX_INFO 解析失败", exception);
        }
    }

    /**
     * 把固件镜像写入盒子 {@code /tmp/Auto_Box_Update.img}，随后盒子会自行开始升级，
     * 并通过 177/187 两条命令回报进度与结果。
     *
     * @param image 固件镜像原始字节（{@code Auto_Box_Update.img}）
     * @return 是否成功下发
     */
    public boolean sendFirmwareImage(byte[] image) {
        if (closed || !protocolReady || image == null || image.length == 0) {
            publish("固件下发被拒绝：协议未就绪或镜像为空");
            return false;
        }
        if (image.length > MAX_FIRMWARE_SIZE) {
            publish("固件包过大：" + image.length + " 字节（上限 50MB）");
            return false;
        }
        try {
            uploadFile(FIRMWARE_BOX_PATH, image);
            publish("固件已下发到盒子，等待盒子回报升级进度");
            return true;
        } catch (IOException exception) {
            publishDiagnostic("固件下发失败：" + exception.getMessage());
            if (listener != null) listener.onError("固件下发失败", exception);
            return false;
        }
    }

    /** 设置升级成功后是否主动重启盒子，默认开启。 */
    public void setRebootAfterUpgrade(boolean enabled) {
        rebootAfterUpgrade = enabled;
    }

    /**
     * 检查某个 {@code CMD_UPDATE} 状态是否属于「升级成功」，需要主动重启盒子。
     *
     * <p>{@code 2} 为普通升级成功，{@code 6} 为 OTA 升级成功。
     */
    static boolean shouldRebootAfterUpgrade(int status) {
        return status == 2 || status == 6;
    }

    /**
     * 构造重启命令帧（{@code CMD_BOX_REBOOT} 206）。
     *
     * <p>与其它命令不同，这一帧必须保持原版的明文形态：原版
     * {@code oO0O00o._oOoOOO()} 在负载长度为 0 时直接返回，既不调用 native 加密，
     * 也不会把 magic 切成 {@code 0x55BB55BB}。这样即使 {@code libnx1} 未加载或
     * 加密状态已失步，重启指令依然能送达。
     */
    static byte[] createRebootFrame() {
        return ProtocolFrame.encodeLegacy(CMD_BOX_REBOOT, new byte[0]);
    }

    /**
     * 立即要求盒子重启。
     *
     * @return 指令是否写入 USB（不代表盒子已经重启）
     */
    public boolean rebootBox() {
        if (closed) {
            return false;
        }
        try {
            writeFrame(createRebootFrame());
            publish(protocolReady
                    ? "已向盒子发送重启指令（CMD 206）"
                    : "已向盒子发送重启指令（CMD 206，协议未就绪时也可发送）");
            return true;
        } catch (IOException exception) {
            // 盒子自行重启时 USB 已断开，写失败属正常情况，不要当成错误弹窗。
            publish("盒子重启指令未送达：" + exception.getMessage()
                    + "（盒子可能已在自行重启）");
            return false;
        }
    }

    /**
     * 构造手动断开命令帧（{@code CMD_MANUAL_DISCONNECT} 15）。
     *
     * <p>零负载命令，必须保持原版的明文形态：既不加密也不切 {@code 0x55BB55BB}。
     */
    static byte[] createManualDisconnectFrame() {
        return ProtocolFrame.encodeLegacy(CMD_MANUAL_DISCONNECT, new byte[0]);
    }

    /**
     * 要求盒子断开当前连接的手机（{@code CMD_MANUAL_DISCONNECT} 15）。
     *
     * <p>下发后会压制自动回连 {@link #MANUAL_DISCONNECT_COOLDOWN_MS} 毫秒：盒子断开手机
     * 后会进入等待阶段并回调 {@link #maybeAutoConnect()}，不压制就会立刻又连回来。
     * 用户点名回连（cmd 17）或盒子重新上报手机工作时会提前解除压制。
     *
     * @return 指令是否写入 USB（不代表手机已经断开）
     */
    public boolean requestManualDisconnect() {
        if (closed) {
            publishDiagnostic("断开手机失败：连接已关闭");
            return false;
        }
        // 先占住抑制窗口：即使这一帧写失败，也要避免随后触发的自动回连
        autoConnectSuppressedUntil = SystemClock.elapsedRealtime() + MANUAL_DISCONNECT_COOLDOWN_MS;
        autoConnectRequested = false;
        try {
            writeFrame(createManualDisconnectFrame());
            publish("已要求盒子断开当前手机（CMD 15），"
                    + (MANUAL_DISCONNECT_COOLDOWN_MS / 1000) + " 秒内不再自动回连");
            return true;
        } catch (IOException exception) {
            publish("断开手机指令未送达：" + exception.getMessage());
            return false;
        }
    }

    /** 升级成功后延时下发重启，避免抢在成功回报之前掐断 USB。 */
    private void scheduleRebootAfterUpgrade() {
        if (!rebootAfterUpgrade || rebootRequested || closed) {
            return;
        }
        rebootRequested = true;
        Thread rebootThread = new Thread(() -> {
            try {
                Thread.sleep(UPGRADE_REBOOT_DELAY_MS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!closed) {
                rebootBox();
            }
        }, "autoplay-box-reboot");
        rebootThread.setDaemon(true);
        rebootThread.start();
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
            int consecutiveFailures = 0;
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
                        consecutiveFailures = 0;
                    } catch (IOException exception) {
                        // 单次写超时多是 USB 忙，不能因此判定整个连接失败；
                        // 连续失败才说明链路真的断了，交给上层重连。
                        consecutiveFailures++;
                        publishDiagnostic("盒子心跳发送失败（第 "
                                + consecutiveFailures + " 次）：" + exception.getMessage());
                        if (consecutiveFailures >= MAX_HEARTBEAT_FAILURES) {
                            publish("盒子心跳连续发送失败，正在重新连接");
                            close();
                            // close() 会让读线程静默退出（finally 里不再回调），
                            // 所以这里显式通知上层释放连接并重连。
                            if (listener != null) listener.onSessionEnded();
                            return;
                        }
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
                    publishDiagnostic("触控事件发送失败：" + exception.getMessage());
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
        // 新一轮手机会话开始时再报一次副屏尺寸，避免盒子重启 CarPlay 服务后丢失配置
        flushNaviScreenInfo();
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
                Log.i("AutoPlayKeys", "电话音频控制=" + control + "，电话状态=" + callState
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
                // 手机已经连上，手动断开的抑制窗口不再需要
                autoConnectSuppressedUntil = 0;
                publishPhoneStatus(phoneTypeName(phoneType) + " 工作中");
                resendNaviScreenInfoIfStale();
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
                autoConnectSuppressedUntil = 0;
                publishPhoneStatus(phoneTypeName(phoneType) + " 已连接并工作");
                // 盒子通常在这一阶段才真正建立 CarPlay 会话，此时补一次副屏尺寸
                resendNaviScreenInfoIfStale();
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

    /**
     * 处理副屏帧 {@code CMD_DASHBOARD_FRAME}(44)。
     *
     * <p>解析失败只记日志：副屏是可选功能，单帧损坏不应打断主屏投屏。
     */
    private void handleDashboardFrame(byte[] payload) {
        if (payload == null || payload.length <= DASHBOARD_HEADER_SIZE) {
            Log.w("BoxProtocolSession", "CMD_DASHBOARD_FRAME 载荷过短：" + (payload == null ? 0 : payload.length));
            return;
        }
        try {
            VideoFrame frame = VideoFrame.parseDashboard(payload);
            if (!dashboardFrameSeen) {
                dashboardFrameSeen = true;
                Log.i(TAG, "收到首帧副屏画面 " + frame.getWidth() + "x" + frame.getHeight()
                        + "，" + frame.getH264Data().length + " 字节");
            }
            if (listener != null) {
                listener.onDashboardFrame(frame);
            }
        } catch (IOException exception) {
            Log.w("BoxProtocolSession", "CMD_DASHBOARD_FRAME 解析失败", exception);
        }
    }

    /**
     * 告知盒子副屏画面尺寸（{@code CMD_BOX_SETTINGS} 25 的 {@code naviScreenInfo}）。
     *
     * <p>原版用 Android {@code Presentation} 绑定真实副屏的 {@code getRealMetrics()}；
     * playAuto 用悬浮窗模拟，因此改为上报悬浮窗的像素尺寸与 DPI。
     */
    public void sendNaviScreenInfo(int widthPx, int heightPx, int fps, int dpi) {
        if (closed || widthPx <= 0 || heightPx <= 0) {
            return;
        }
        // 悬浮窗通常早于协议握手创建，未就绪时先记住尺寸，等 applyRuntimeConfig() 补发。
        pendingNaviScreenInfo = new int[]{widthPx, heightPx, fps, dpi};
        if (!protocolReady) {
            return;
        }
        writeNaviScreenInfo(pendingNaviScreenInfo);
    }

    /**
     * 重新下发缓存的副屏尺寸。
     *
     * <p>握手完成、手机重新插入（盒子会新起一轮 CarPlay 会话）时各发一次，
     * 保证盒子始终持有当前悬浮窗的尺寸。
     */
    private void flushNaviScreenInfo() {
        int[] info = pendingNaviScreenInfo;
        if (info == null || !protocolReady || closed) {
            return;
        }
        writeNaviScreenInfo(info);
    }

    private void writeNaviScreenInfo(int[] info) {
        byte[] payload = BoxSetting.naviScreenInfo(info[0], info[1], info[2], info[3])
                .payload();
        lastNaviScreenInfoSentAt = SystemClock.elapsedRealtime();
        Log.i(TAG, "上报副屏尺寸 " + info[0] + "x" + info[1] + " @" + info[2]
                + " fps, " + info[3] + " dpi");
        runOnControlExecutor(() -> writeFrame(
                ProtocolFrame.encode(25, payload, cryptEnabled, nonce)));
    }

    /**
     * 手机刚进入工作态时，若副屏尺寸已超过 {@link #NAVI_SCREEN_INFO_REFRESH_MS} 没上报，再发一次。
     *
     * <p>盒子在收到副屏尺寸前不会产生 {@code CMD_DASHBOARD_FRAME}(44)，而
     * 握手完成到手机真正投屏之间盒子可能重启 CarPlay 服务，所以阶段转变时
     * 补报一次比只报一次更可靠；用时间戳限速避免盒子反复上报同阶段时刷帧。
     */
    private void resendNaviScreenInfoIfStale() {
        int[] info = pendingNaviScreenInfo;
        if (info == null || !protocolReady || closed) {
            return;
        }
        if (SystemClock.elapsedRealtime() - lastNaviScreenInfoSentAt
                < NAVI_SCREEN_INFO_REFRESH_MS) {
            return;
        }
        writeNaviScreenInfo(info);
    }

    /**
     * 记录 {@code CMD 42} 的子类型与内容摘要，供排查「只有歌词没有导航」。
     *
     * <p>导航（200）与来电（100）低频且关键，每次都记；媒体（1）约 0.5 s 一条，
     * 只每 5 秒记一次，避免刷屏。
     */
    private void logDashboardSubtype(int subtype, int length, String data) {
        long now = SystemClock.elapsedRealtime();
        Long last = dashboardSubtypeLogAt.get(subtype);
        boolean key = subtype == 200 || subtype == 100;
        if (!key && last != null && now - last < 5000L) {
            return;
        }
        dashboardSubtypeLogAt.put(subtype, now);
        // 2/3 = 专辑封面 JPEG，202/203 = 原始数据，内容是二进制，不打印
        boolean binary = subtype == 2 || subtype == 3 || subtype == 202 || subtype == 203;
        String preview = binary ? "<二进制>"
                : data.length() > 300 ? data.substring(0, 300) + "…" : data;
        Log.i("AutoPlayNavigation", "CMD 42 子类型 " + subtype + "，"
                + length + " 字节：" + preview);
    }

    private void handleDashboardData(byte[] payload) {
        if (payload.length < 4) {
            android.util.Log.e("AutoPlayNavigation", "Dashboard packet shorter than subtype header");
            if (listener != null) listener.onNavigationData("数据错误：命令 42 缺少子类型头");
            return;
        }
        int subtype = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt();
        // 子类型 1 / 100 / 200 的 JSON 末尾带 \0，先裁掉再解析
        int end = payload.length;
        while (end > 4 && payload[end - 1] == 0) end--;
        if (end <= 4) {
            if (listener != null) {
                listener.onNavigationData("收到 CMD 42 / " + subtype + "，但负载为空");
            }
            return;
        }
        String data = new String(payload, 4, end - 4, java.nio.charset.StandardCharsets.UTF_8);
        logDashboardSubtype(subtype, payload.length, data);

        if (subtype == 1) {
            // 媒体信息（歌名/歌手/歌词/进度），增量包
            try {
                org.json.JSONObject media = new org.json.JSONObject(data);
                if (listener != null) listener.onMediaUpdate(media);
            } catch (org.json.JSONException exception) {
                android.util.Log.e("AutoPlayNavigation", "Invalid media JSON", exception);
                if (listener != null) {
                    listener.onNavigationData("媒体 JSON 解析失败：" + exception.getMessage()
                            + "\n原始数据：" + data);
                }
            }
            return;
        }
        if (subtype != 200) {
            if (listener != null) listener.onNavigationData("收到 CMD 42 / " + subtype
                    + "，载荷 " + payload.length + " 字节（媒体封面/来电或未使用子类型，不记录内容）");
            return;
        }
        if (end - 4 > 16384) {
            android.util.Log.e("AutoPlayNavigation", "Navigation payload exceeds 16 KiB");
            if (listener != null) listener.onNavigationData("数据错误：导航 JSON 超过 16 KiB，未保存");
            return;
        }
        try {
            org.json.JSONObject navigation = new org.json.JSONObject(data);
            if (listener != null) listener.onNavigationUpdate(navigation);
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
            if (subcommand == 506 && listener != null) listener.onNavigationStarted();
            if (subcommand == 507 && listener != null) listener.onNavigationEnded();
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
        String pairList = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        List<PairedDevice> devices = parseBluetoothPairList(pairList);
        pairedDevices = Collections.unmodifiableList(devices);
        pairedDeviceCount = devices.size();
        publishPhoneStatus(pairedDeviceCount == 0
                ? "没有已配对手机，自动连接不可用"
                : "已配对手机：" + pairedDeviceCount + " 台");
        if (listener != null) {
            listener.onPairedDevices(pairedDevices);
        }
        maybeAutoConnect();
    }

    /**
     * 解析盒子 {@code CMD_BLUETOOTH_PAIR_LIST}(18) 的负载。
     *
     * <p>原版格式：每行「17 字符 MAC + 设备名」，行间以 {@code \n} 分隔，
     * 整体可能带结尾 {@code \0}（原版按 {@code len - 1} 截断）。解析时对
     * 长度不足 17、MAC 格式非法的行直接丢弃，避免脏数据被当成设备。
     */
    static List<PairedDevice> parseBluetoothPairList(String raw) {
        List<PairedDevice> devices = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return devices;
        }
        int end = raw.length();
        while (end > 0 && raw.charAt(end - 1) == '\0') end--;
        String body = raw.substring(0, end);
        for (String line : body.split("\\r?\\n")) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() < MAC_LENGTH) {
                continue;
            }
            String mac = line.substring(0, MAC_LENGTH).trim();
            if (!isValidBluetoothMac(mac)) {
                continue;
            }
            String name = line.substring(MAC_LENGTH).trim();
            devices.add(new PairedDevice(mac.toUpperCase(java.util.Locale.ROOT), name));
        }
        return devices;
    }

    /** MAC 在协议里固定 17 个 ASCII 字符（{@code AA:BB:CC:DD:EE:FF}）。 */
    static final int MAC_LENGTH = 17;

    /**
     * 校验 {@code AA:BB:CC:DD:EE:FF} 形式的蓝牙地址。
     *
     * <p>原版 {@code sendAutoConnByBtAddr} 只检查字节数是否为 17，
     * 这里额外校验字符集，避免把「名字里恰好含 17 个字符」的行发出去。
     */
    static boolean isValidBluetoothMac(String mac) {
        if (mac == null || mac.length() != MAC_LENGTH) {
            return false;
        }
        // 分隔符必须全程一致（盒子用 ':'，用户手输也可能用 '-'）
        char separator = mac.charAt(2);
        if (separator != ':' && separator != '-') {
            return false;
        }
        for (int index = 0; index < MAC_LENGTH; index++) {
            char c = mac.charAt(index);
            if (index % 3 == 2) {
                if (c != separator) {
                    return false;
                }
            } else if (Character.digit(c, 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** 构造 {@code CMD_AUTO_CONN_BT_ADDR}(17) 的 17 字节 ASCII 负载。 */
    static byte[] createAutoConnectAddressPayload(String mac) {
        if (!isValidBluetoothMac(mac)) {
            throw new IllegalArgumentException("蓝牙地址格式不正确：" + mac);
        }
        // 统一成盒子认识的大写 ':' 形式，避免用户用 '-' 输入时盒子无法匹配
        String normalized = mac.toUpperCase(java.util.Locale.ROOT).replace('-', ':');
        return normalized.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * 点名让盒子去连这一台手机（{@code CMD_AUTO_CONN_BT_ADDR} 17）。
     *
     * <p>与 {@link #maybeAutoConnect()} 的 {@code 8 / 1002} 互补：1002 让盒子在
     * 已配对列表里自己挑，17 则指定具体 MAC。
     *
     * @return 指令是否写入 USB（不代表手机已连上）
     */
    public boolean requestAutoConnectTo(String mac) {
        if (closed || !protocolReady) {
            publishDiagnostic("指定回连失败：协议未就绪");
            return false;
        }
        byte[] payload;
        try {
            payload = createAutoConnectAddressPayload(mac);
        } catch (IllegalArgumentException exception) {
            publishDiagnostic("指定回连失败：" + exception.getMessage());
            return false;
        }
        try {
            writeFrame(ProtocolFrame.encode(CMD_AUTO_CONN_BT_ADDR, payload, cryptEnabled, nonce));
            publish("已请求盒子回连指定手机：" + mac.toUpperCase(java.util.Locale.ROOT));
            autoConnectRequested = true;
            // 用户主动点名回连，解除手动断开留下的抑制窗口
            autoConnectSuppressedUntil = 0;
            return true;
        } catch (IOException exception) {
            publishDiagnostic("指定回连失败：" + exception.getMessage());
            if (listener != null) listener.onError("指定回连失败", exception);
            return false;
        }
    }

    /** 盒子上报的已配对手机列表，未收到 cmd 18 时为空。 */
    public List<PairedDevice> getPairedDevices() {
        return pairedDevices;
    }

    /**
     * 构造写回盒子的已配对列表负载（{@code CMD_BLUETOOTH_PAIR_LIST} 18）。
     *
     * <p>格式与盒子下发时一致：每行「17 字符 MAC + 设备名 + {@code \n}」，
     * 整体以 {@code \0} 结尾。原版 {@code O00o_OooO___OOo_} 长按删除某条后
     * 就是用这个格式回写的。
     */
    static byte[] createPairedListPayload(List<PairedDevice> devices) {
        StringBuilder builder = new StringBuilder();
        if (devices != null) {
            for (PairedDevice device : devices) {
                if (device == null || !isValidBluetoothMac(device.getMac())) {
                    continue;
                }
                String name = device.getName() == null ? "" : device.getName();
                // 名称里不能带换行，否则盒子会把它当成另一条记录
                name = name.replace('\r', ' ').replace('\n', ' ');
                builder.append(device.getMac().toUpperCase(java.util.Locale.ROOT))
                        .append(name)
                        .append('\n');
            }
        }
        builder.append('\0');
        return builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 把已配对列表写回盒子（{@code CMD_BLUETOOTH_PAIR_LIST} 18）。
     *
     * <p>盒子以主机下发的列表为准，因此删除条目只需回写一份新列表。
     *
     * @return 指令是否写入 USB（不代表盒子已接受）
     */
    public boolean sendPairedDeviceList(List<PairedDevice> devices) {
        if (closed || !protocolReady) {
            publishDiagnostic("写回配对列表失败：协议未就绪");
            return false;
        }
        List<PairedDevice> snapshot = devices == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(devices));
        byte[] payload = createPairedListPayload(snapshot);
        try {
            writeFrame(ProtocolFrame.encode(CMD_BLUETOOTH_PAIR_LIST, payload, cryptEnabled, nonce));
            pairedDevices = snapshot;
            pairedDeviceCount = snapshot.size();
            publish("已把配对列表写回盒子：" + pairedDeviceCount + " 台");
            if (listener != null) {
                listener.onPairedDevices(snapshot);
            }
            return true;
        } catch (IOException exception) {
            publishDiagnostic("写回配对列表失败：" + exception.getMessage());
            if (listener != null) listener.onError("写回配对列表失败", exception);
            return false;
        }
    }

    /**
     * 从已配对列表里删除某一台手机并回写盒子。
     *
     * @param mac 目标手机的蓝牙地址
     * @return 指令是否写入 USB；地址不存在时返回 {@code false}
     */
    public boolean removePairedDevice(String mac) {
        if (!isValidBluetoothMac(mac)) {
            publishDiagnostic("删除配对失败：蓝牙地址格式不正确");
            return false;
        }
        String target = mac.toUpperCase(java.util.Locale.ROOT).replace('-', ':');
        List<PairedDevice> remaining = new ArrayList<>();
        boolean found = false;
        for (PairedDevice device : pairedDevices) {
            if (device.getMac().equalsIgnoreCase(target)) {
                found = true;
                continue;
            }
            remaining.add(device);
        }
        if (!found) {
            publishDiagnostic("删除配对失败：列表里没有 " + target);
            return false;
        }
        boolean sent = sendPairedDeviceList(remaining);
        if (sent && remaining.isEmpty()) {
            // 列表清空后自动连接不可用，需同步状态
            autoConnectRequested = false;
        }
        return sent;
    }

    /** 当前是否处在「手动断开后的自动回连抑制窗口」内。 */
    private boolean autoConnectSuppressed() {
        return autoConnectSuppressedUntil > SystemClock.elapsedRealtime();
    }

    private void maybeAutoConnect() {
        if (!protocolReady || !boxSupportsAutoConnect || !autoConnectEnabled
                || pairedDeviceCount <= 0 || phoneWorking || bluetoothConnected
                || autoConnectRequested || autoConnectSuppressed() || closed) {
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
            if (!closed && autoConnectEnabled && !phoneWorking && !bluetoothConnected
                    && !autoConnectSuppressed()) {
                try {
                    byte[] payload = ByteBuffer.allocate(4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(1002)
                            .array();
                    writeFrame(ProtocolFrame.encode(
                            CMD_AUTO_CONNECT, payload, cryptEnabled, nonce));
                    publish("已在手机等待阶段请求盒子自动连接");
                } catch (IOException exception) {
                    publishDiagnostic("自动连接请求失败：" + exception.getMessage());
                    autoConnectRequested = false;
                }
            } else {
                autoConnectRequested = false;
            }
        }, "autoplay-auto-connect");
        autoConnectThread.start();
    }

    /**
     * 取命令中有意义的首个整数值，供指令日志显示（阶段 / 子命令 / 状态 / 手机类型）。
     *
     * @return 无意义的命令或负载不足 4 字节时返回 {@code -1}
     */
    static int commandValue(int command, byte[] payload) {
        if (payload == null || payload.length < 4) {
            return -1;
        }
        switch (command) {
            case CMD_PLUG:
            case CMD_PHASE:
            case CMD_CARPLAY:
            case CMD_UPDATE_PROGRESS:
            case CMD_UPDATE:
            case 42:
                // cmd 42 的首个 int 是子类型（1 媒体 / 200 导航 / 100 来电 …）
                return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            default:
                return -1;
        }
    }

    /** 命令号 → 协议名，用于「盒子指令日志」窗口；未知命令返回 {@code null}。 */
    static String describeCommand(int command) {
        switch (command) {
            case 1: return "CMD_OPEN";
            case CMD_PLUG: return "CMD_PLUG";
            case CMD_PHASE: return "CMD_PHASE";
            case 4: return "CMD_PLUGOUT";
            case CMD_VIDEO_FRAME: return "CMD_VIDEO_FRAME";
            case 7: return "CMD_AUDIO_FRAME";
            case CMD_CARPLAY: return "CMD_CARPLAY_CMD";
            case 11: return "CMD_CARPLAY_MODE_CHANGE";
            case 13: return "CMD_BOX_BLUETOOTH_NAME";
            case 14: return "CMD_BOX_WIFI_NAME";
            case CMD_BLUETOOTH_PAIR_LIST: return "CMD_BLUETOOTH_PAIR_LIST";
            case 19: return "CMD_BLUETOOTH_ONLINE_LIST";
            case 22: return "CMD_CAMERA_INFO";
            case 24: return "CMD_HICAR_URL";
            case CMD_BOX_INFO: return "CMD_BOX_INFO";
            case 42: return "CMD_DASHBOARD_DATA";
            case 43: return "CMD_CONNECTION_PIN_CODE";
            case CMD_DASHBOARD_FRAME: return "CMD_DASHBOARD_FRAME";
            case 119: return "CMD_APP_RESET";
            case 136: return "CMD_DEBUG_TEST";
            case CMD_UPLOAD_FILE: return "CMD_FILE";
            case 161: return "CMD_APP_LOG_GET";
            case 163: return "CMD_CRYPT_BOX_INFO";
            case CMD_UPDATE_PROGRESS: return "CMD_UPDATE_PROGRESS";
            case CMD_UPDATE: return "CMD_UPDATE";
            case 204: return "CMD_BOX_SOFTWARE_VERSION";
            case CMD_ENABLE_CRYPT: return "CMD_ENABLE_CRYPT";
            case 255: return "（忽略）";
            default: return null;
        }
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
                throw new IOException("车机盒已切到加密模式，但本机加密库不可用"
                        + "（多为 32 位系统且 libnx1.so 未打补丁），无法解密");
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
        // 每次尝试都给足原版同款的 4 秒；整体再多留一次重试的余量。
        long deadline = SystemClock.elapsedRealtime() + (long) USB_WRITE_TIMEOUT_MS * 2;
        while (offset < length && !closed) {
            int count = connection.bulkWrite(data, start + offset, length - offset,
                    USB_WRITE_TIMEOUT_MS);
            if (count <= 0) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw new IOException("USB bulk write timed out（盒子可能正忙）");
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

    /** 非致命问题：只在连接详情留档，不改变连接状态。 */
    private void publishDiagnostic(String message) {
        Log.i(TAG, message);
        if (listener != null) {
            listener.onDiagnostic(message);
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
