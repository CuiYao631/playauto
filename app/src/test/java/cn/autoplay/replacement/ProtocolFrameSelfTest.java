package cn.autoplay.replacement;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ProtocolFrameSelfTest {
    public static void main(String[] args) throws Exception {
        testLegacyEncodeAndDecode();
        testCryptHeaderRecognition();
        testCarPlayOpenPayload();
        testVideoFrameParsing();
        testDashboardFrameParsing();
        testVideoFrameQueue();
        testAudioFrameParsing();
        testTouchFrameEncoding();
        testCarLogoPayloadEncoding();
        testRejectsInvalidHeaders();
        testBoxSettings();
        testVideoGeometry();
        testOriginalVideoOptions();
        testVendorKeys();
        testHeadlightNightMode();
        testBoxFirmwarePayload();
        testAutoConnectByBluetoothAddress();
        testManualDisconnectFrame();
        testSteeringKeyCodeParsing();
        testFrameRateNormalization();
        testNightModeSelection();
        testUpdateComparison();
    }

    private static void testUpdateComparison() {
        // 版本号格式识别：构建脚本产出的 YYYY.MM.DD.HHMM
        check(UpdateChecker.isTimestampVersion("2026.11.16.1418"), "timestamp version recognized");
        check(!UpdateChecker.isTimestampVersion("2026.11.16"), "short version rejected");
        check(!UpdateChecker.isTimestampVersion("2026-11-16.1418"), "wrong separator rejected");
        check(!UpdateChecker.isTimestampVersion("2026.11.16.14a8"), "non-digit rejected");
        check(!UpdateChecker.isTimestampVersion(null), "null version rejected");
        check(!UpdateChecker.isTimestampVersion(""), "empty version rejected");

        // 回归：本地 2026.11.16 被误判为需要升级到 2026.11.13（远程 versionCode 更大）
        check(!UpdateChecker.isNewer("2026.11.13.1700", 2, "2026.11.16.1418", 1),
                "older remote version must not trigger update even with larger versionCode");
        // 正常升级
        check(UpdateChecker.isNewer("2026.11.17.0900", 3, "2026.11.16.1418", 1),
                "newer remote version triggers update");
        // 完全相同
        check(!UpdateChecker.isNewer("2026.11.16.1418", 2, "2026.11.16.1418", 2),
                "identical versions report no update");
        // 同一天不同分钟
        check(UpdateChecker.isNewer("2026.11.16.1430", 2, "2026.11.16.1418", 2),
                "later minute same day triggers update");
        check(!UpdateChecker.isNewer("2026.11.16.1400", 2, "2026.11.16.1418", 2),
                "earlier minute same day reports no update");
        // 跨年
        check(UpdateChecker.isNewer("2027.01.01.0000", 1, "2026.12.31.2359", 1),
                "year rollover compares correctly");
        // 版本号格式异常时退回 versionCode 比较
        check(UpdateChecker.isNewer("1.2.3", 5, "2026.11.16.1418", 4),
                "falls back to versionCode when remote format is unknown");
        check(!UpdateChecker.isNewer("1.2.3", 3, "2026.11.16.1418", 4),
                "fallback respects lower versionCode");
        check(!UpdateChecker.isNewer("2026.11.17.0900", 1, "custom-build", 4),
                "falls back to versionCode when current format is unknown");

        // 更新弹窗正文：使用 update.json 的真实更新说明，并做整理与兜底
        check(UpdateChecker.formatNotes("- 修复 A\n- 优化 B").equals("- 修复 A\n- 优化 B"),
                "notes preserved");
        check(UpdateChecker.formatNotes("- 修复 A\\n- 优化 B").equals("- 修复 A\n- 优化 B"),
                "escaped newlines restored");
        check(UpdateChecker.formatNotes("  \n\n - 修复 A \n\n\n- 优化 B \n ").equals("- 修复 A\n- 优化 B"),
                "blank lines and padding trimmed");
        check(UpdateChecker.formatNotes("").equals(UpdateChecker.DEFAULT_NOTES),
                "empty notes fall back to default");
        check(UpdateChecker.formatNotes("   \n  ").equals(UpdateChecker.DEFAULT_NOTES),
                "whitespace-only notes fall back to default");
        check(UpdateChecker.formatNotes(null).equals(UpdateChecker.DEFAULT_NOTES),
                "null notes fall back to default");

        // Release 描述与提交信息一致，弹窗正文不得改动 Markdown 等原有内容
        check(UpdateChecker.formatNotes("## 更新内容\n- 修复 A").equals("## 更新内容\n- 修复 A"),
                "markdown headings preserved");
        check(UpdateChecker.formatNotes("- 修复 A\n\n---\n\n- 优化 B").equals("- 修复 A\n---\n- 优化 B"),
                "horizontal rule preserved, blank lines collapsed");
        check(UpdateChecker.formatNotes("修复：\n\n1. A\n2. B").equals("修复：\n1. A\n2. B"),
                "multi-line commit body preserved");

        // tag 去前缀
        check(UpdateChecker.normalizeTag("v2026.11.16.1444").equals("2026.11.16.1444"),
                "v prefix stripped");
        check(UpdateChecker.normalizeTag("2026.11.16.1444").equals("2026.11.16.1444"),
                "plain tag preserved");
        check(UpdateChecker.normalizeTag(" version ").equals("version"),
                "non-numeric prefix kept");
        check(UpdateChecker.normalizeTag(null).isEmpty(), "null tag becomes empty");

        // Release 资产挑选（android.jar 的 org.json 是桩实现，故测试纯函数版本）
        check(UpdateChecker.pickApkUrl(
                new String[]{"notes.txt", "AutoPlayReplacement-debug.apk"},
                new String[]{"u1", "u2"}).equals("u2"), "exact apk asset preferred");
        check(UpdateChecker.pickApkUrl(
                new String[]{"other.apk"}, new String[]{"u3"}).equals("u3"),
                "fallback to any apk asset");
        check(UpdateChecker.pickApkUrl(new String[]{}, new String[]{}).isEmpty(),
                "no assets yields empty url");
        check(UpdateChecker.pickApkUrl(null, null).isEmpty(),
                "missing assets yields empty url");
        check(UpdateChecker.pickApkUrl(
                new String[]{"a.txt"}, new String[]{""}).isEmpty(),
                "asset without url skipped");
    }

    private static void testFrameRateNormalization() {
        // 设置页只提供 30 / 60，历史九档取值必须收敛，否则选项下标查找会抛异常。
        check(java.util.Arrays.equals(VideoOptions.SELECTABLE_FRAME_RATES, new int[]{60, 30}),
                "settings only expose 60 and 30 fps");
        check(VideoOptions.normalizeFrameRate(60) == 60, "60 stays 60");
        check(VideoOptions.normalizeFrameRate(30) == 30, "30 stays 30");
        check(VideoOptions.normalizeFrameRate(55) == 60, "55 collapses to 60");
        check(VideoOptions.normalizeFrameRate(50) == 60, "50 collapses to 60");
        check(VideoOptions.normalizeFrameRate(45) == 60, "45 collapses to 60");
        check(VideoOptions.normalizeFrameRate(40) == 30, "40 collapses to 30");
        check(VideoOptions.normalizeFrameRate(35) == 30, "35 collapses to 30");
        check(VideoOptions.normalizeFrameRate(25) == 30, "25 collapses to 30");
        check(VideoOptions.normalizeFrameRate(20) == 30, "20 collapses to 30");
        check(VideoOptions.normalizeFrameRate(0) == 30, "unset collapses to default 30");
        check(VideoOptions.normalizeFrameRate(-1) == 30, "negative collapses to default");
        // 归一化后的值必须能通过 effectiveFrameRate 校验
        for (int raw : new int[]{20, 25, 30, 35, 40, 45, 50, 55, 60}) {
            int normalized = VideoOptions.normalizeFrameRate(raw);
            check(VideoOptions.effectiveFrameRate(VideoOptions.HARDWARE, normalized) == normalized,
                    "normalized " + raw + " is accepted by effectiveFrameRate");
        }
        // 软解仍固定 25，不受归一化影响
        check(VideoOptions.effectiveFrameRate(VideoOptions.SOFTWARE, 60) == 25,
                "software decoder still negotiates 25fps");
    }

    private static void testNightModeSelection() {
        // 夜间模式只暴露「跟随手机(2)」「大灯联动(3)」，历史值 0/1 需能安全回退。
        int[] values = {2, 3};
        check(SettingsScreens.selectedValueIndex(values, 2, 0) == 0, "follow-phone maps to index 0");
        check(SettingsScreens.selectedValueIndex(values, 3, 0) == 1, "headlight maps to index 1");
        check(SettingsScreens.selectedValueIndex(values, 0, 0) == 0, "legacy 0 falls back to default");
        check(SettingsScreens.selectedValueIndex(values, 1, 0) == 0, "legacy 1 falls back to default");
        check(SettingsScreens.selectedValueIndex(values, -5, 0) == 0, "unknown value falls back");
        check(SettingsScreens.selectedValueIndex(values, 99, 1) == 1, "unknown value uses given default");
    }

    private static void testSteeringKeyCodeParsing() {
        String androidPrefix = "steering_key_";
        // 正常键
        check(SettingsScreens.parseAndroidKeyCode("steering_key_25", androidPrefix) == 25,
                "plain keycode suffix parses");
        check(SettingsScreens.parseAndroidKeyCode("steering_key_0", androidPrefix) == 0,
                "zero keycode parses");
        // 长按槽位：键码正确解析且标记为长按
        SettingsScreens.AndroidBinding held =
                SettingsScreens.parseAndroidBinding("steering_key_long_25", androidPrefix);
        check(held != null && held.keyCode == 25 && held.longPress,
                "long_ suffix parses as long-press binding");
        SettingsScreens.AndroidBinding single =
                SettingsScreens.parseAndroidBinding("steering_key_25", androidPrefix);
        check(single != null && !single.longPress, "plain suffix is single press");
        // 回归：曾因 Integer.parseInt("long_25") 抛异常闪退
        check(SettingsScreens.parseAndroidKeyCode("steering_key_long_25", androidPrefix) == 25,
                "legacy long_ suffix no longer throws");
        check(SettingsScreens.parseAndroidBinding("steering_key_long_", androidPrefix) == null,
                "long_ without keycode returns null");
        check(SettingsScreens.parseAndroidBinding("steering_key_long_ab", androidPrefix) == null,
                "long_ with letters returns null");
        check(SettingsScreens.parseAndroidKeyCode("steering_key_", androidPrefix) == null,
                "empty suffix returns null");
        check(SettingsScreens.parseAndroidKeyCode("steering_key_25a", androidPrefix) == null,
                "trailing letter returns null");
        check(SettingsScreens.parseAndroidKeyCode("steering_key_2 5", androidPrefix) == null,
                "embedded space returns null");
        check(SettingsScreens.parseAndroidKeyCode("steering_key_99999999999", androidPrefix) == null,
                "overflowing suffix returns null instead of throwing");
        // 前缀不匹配
        check(SettingsScreens.parseAndroidKeyCode("steering_vendor_25", androidPrefix) == null,
                "mismatched prefix returns null");
        check(SettingsScreens.parseAndroidKeyCode(null, androidPrefix) == null,
                "null key returns null");
        check(SettingsScreens.parseAndroidKeyCode("steering_key_25", null) == null,
                "null prefix returns null");
        // 厂家协议前缀
        check(SettingsScreens.parseAndroidKeyCode("steering_cs35plus_key_66",
                        "steering_cs35plus_key_") == 66,
                "cs35plus prefix parses");
        check(SettingsScreens.parseAndroidKeyCode("steering_cs35plus_vendor_long_25",
                        "steering_cs35plus_key_") == null,
                "vendor key does not match android prefix");

        // 长按阈值与键名构造
        check(SteeringProtocol.LONG_PRESS_MS == 800L, "long press threshold is 800ms");
        check(SteeringProtocol.androidBindingSuffix(25, false).equals("25"),
                "single binding suffix is bare keycode");
        check(SteeringProtocol.androidBindingSuffix(25, true).equals("long_25"),
                "long binding suffix carries long_ marker");

        testSteeringActionResolution();
    }

    private static void testSteeringActionResolution() {
        int home = 200;
        int siri = 10003;
        // 用户场景：单击主页、长按 Siri
        check(SteeringProtocol.resolveAction(home, siri, 100) == home,
                "short press runs single action (home)");
        check(SteeringProtocol.resolveAction(home, siri, 799) == home,
                "just below threshold still single action");
        check(SteeringProtocol.resolveAction(home, siri, 800) == siri,
                "threshold exactly runs long action (siri)");
        check(SteeringProtocol.resolveAction(home, siri, 1500) == siri,
                "held well past threshold runs long action");

        // 只绑定单击：任何时长都只执行单击
        check(SteeringProtocol.resolveAction(home, -1, 100) == home, "single-only short press");
        check(SteeringProtocol.resolveAction(home, -1, 2000) == home,
                "single-only long hold still runs single action");

        // 只绑定长按：短按保持静默，长按才执行
        check(SteeringProtocol.resolveAction(-1, siri, 100) == -1,
                "long-only short press stays silent");
        check(SteeringProtocol.resolveAction(-1, siri, 900) == siri,
                "long-only long press runs");

        // 都没绑定
        check(SteeringProtocol.resolveAction(-1, -1, 500) == -1, "no bindings resolves to none");

        testLongPressTimerFlow();
    }

    /** 按住满阈值立即触发长按，无需松手；触发后松手不再补发单击。 */
    private static void testLongPressTimerFlow() {
        int home = 200;
        int siri = 10003;
        String key = "key:25";
        SteeringProtocol.PressState state = new SteeringProtocol.PressState();

        // 场景一：按住满 800ms 立即触发长按（此时尚未松手）
        state.begin(key, home, siri, 1000);
        check(state.isPressed(), "state tracks pressed key");
        check(state.longActionIfDue(1500) == -1, "not due yet at 500ms");
        check(!state.isLongFired(), "long press not fired before threshold");
        check(state.longActionIfDue(1800) == siri, "fires siri exactly at 800ms");
        check(state.isLongFired(), "state records long press fired");
        check(state.longActionIfDue(2500) == -1, "does not fire twice");
        // 松手时不再补发单击
        check(state.finish(2600) == -1, "release after long press sends nothing");
        check(!state.isPressed(), "state resets after release");

        // 场景二：短按松手，执行单击；计时器后续调用不应再触发
        state.begin(key, home, siri, 3000);
        check(state.finish(3200) == home, "short press releases home");
        check(state.longActionIfDue(4000) == -1, "timer after release does nothing");

        // 场景三：只绑长按，短按保持静默
        state.begin(key, -1, siri, 5000);
        check(state.finish(5200) == -1, "long-only short press is silent");
        // 长按正常触发
        state.begin(key, -1, siri, 6000);
        check(state.longActionIfDue(6800) == siri, "long-only long press fires");

        // 场景四：未按下时计时器不应误触发
        state.reset();
        check(state.longActionIfDue(9000) == -1, "idle timer does nothing");
        check(state.finish(9100) == -1, "idle release does nothing");

        // 场景五：不同按键的松手不会误判为当前按下
        state.begin(key, home, siri, 10000);
        check(!state.isPressed("VR"), "different key is not treated as pressed");
        check(state.isPressed(key), "matching key recognized");
        state.reset();

        testVendorVoiceDefaults();
    }

    /** 厂家语音键：单击主页、长按 Siri 的默认绑定与学习覆盖。 */
    private static void testVendorVoiceDefaults() {
        check(SteeringProtocol.DEFAULT_VR_SINGLE == 200, "default single is home");
        check(SteeringProtocol.DEFAULT_VR_LONG == 10003, "default long is siri");
        check(SteeringProtocol.vendorBindingSuffix("VR", false).equals("VR"),
                "vendor single suffix is bare code");
        check(SteeringProtocol.vendorBindingSuffix("VR", true).equals("long_VR"),
                "vendor long suffix carries long_ marker");
        check(VendorKeys.supportsLongPress("VR"), "voice key supports long press");
        check(!VendorKeys.supportsLongPress("MAIN"),
                "MAIN has only press event so no long press");
        check(!VendorKeys.supportsLongPress("NEXT"), "NEXT keeps single press only");
        check(!VendorKeys.supportsLongPress("PRE"), "PRE keeps single press only");

        // 状态机同样适用于厂家广播码
        SteeringProtocol.PressState vendor = new SteeringProtocol.PressState();
        vendor.begin("VR", SteeringProtocol.DEFAULT_VR_SINGLE,
                SteeringProtocol.DEFAULT_VR_LONG, 1000);
        check(vendor.longActionIfDue(1800) == SteeringProtocol.DEFAULT_VR_LONG,
                "vendor VR long press fires siri at 800ms");
        vendor.begin("VR", SteeringProtocol.DEFAULT_VR_SINGLE,
                SteeringProtocol.DEFAULT_VR_LONG, 3000);
        check(vendor.finish(3150) == SteeringProtocol.DEFAULT_VR_SINGLE,
                "vendor VR short press sends home");
    }

    private static void testAutoConnectByBluetoothAddress() {
        // MAC 校验：17 字符 AA:BB:CC:DD:EE:FF
        check(BoxProtocolSession.isValidBluetoothMac("AA:BB:CC:DD:EE:FF"), "uppercase mac accepted");
        check(BoxProtocolSession.isValidBluetoothMac("aa:bb:cc:dd:ee:ff"), "lowercase mac accepted");
        check(BoxProtocolSession.isValidBluetoothMac("00:11:22:33:44:55"), "numeric mac accepted");
        check(!BoxProtocolSession.isValidBluetoothMac("AA:BB:CC:DD:EE"), "16-char mac rejected");
        check(!BoxProtocolSession.isValidBluetoothMac("AA:BB:CC:DD:EE:FF:00"), "20-char mac rejected");
        check(!BoxProtocolSession.isValidBluetoothMac("AA-BB-CC-DD-EE:FF"), "mixed separator rejected");
        check(BoxProtocolSession.isValidBluetoothMac("AA-BB-CC-DD-EE-FF"), "hyphen-only mac accepted");
        check(!BoxProtocolSession.isValidBluetoothMac("GG:BB:CC:DD:EE:FF"), "non-hex rejected");
        check(!BoxProtocolSession.isValidBluetoothMac(""), "empty mac rejected");
        check(!BoxProtocolSession.isValidBluetoothMac(null), "null mac rejected");
        // 名字里恰好含 17 字符的行不能被当成 MAC
        check(!BoxProtocolSession.isValidBluetoothMac("iPhone 15 Pro Max"), "device name rejected");

        // cmd 17 负载：17 字节 ASCII，大写
        byte[] payload = BoxProtocolSession.createAutoConnectAddressPayload("aa:bb:cc:dd:ee:ff");
        check(payload.length == 17, "auto-connect payload is 17 bytes");
        String decoded = new String(payload, StandardCharsets.US_ASCII);
        check(decoded.equals("AA:BB:CC:DD:EE:FF"), "auto-connect payload is uppercased ascii");

        // 用户用 '-' 输入时统一规范化成 ':'
        byte[] hyphenPayload = BoxProtocolSession.createAutoConnectAddressPayload("aa-bb-cc-dd-ee-ff");
        check(new String(hyphenPayload, StandardCharsets.US_ASCII).equals("AA:BB:CC:DD:EE:FF"),
                "hyphen mac normalized to colon form");

        // 非法地址必须拒绝，不能把脏数据写进 USB
        boolean threw = false;
        try {
            BoxProtocolSession.createAutoConnectAddressPayload("not-a-mac");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "invalid mac throws");

        // cmd 18 负载解析：每行「17 字符 MAC + 名字」，结尾 \0
        String raw = "AA:BB:CC:DD:EE:FFiPhone 15\n"
                + "11:22:33:44:55:66Mate 60 Pro\n"
                + "short\n"
                + "ZZ:BB:CC:DD:EE:FFbad mac\n"
                + "\0";
        java.util.List<BoxProtocolSession.PairedDevice> devices =
                BoxProtocolSession.parseBluetoothPairList(raw);
        check(devices.size() == 2, "pair list keeps only valid lines");
        check(devices.get(0).getMac().equals("AA:BB:CC:DD:EE:FF"), "first mac parsed");
        check(devices.get(0).getName().equals("iPhone 15"), "first name parsed");
        check(devices.get(1).getMac().equals("11:22:33:44:55:66"), "second mac parsed");
        check(devices.get(1).getName().equals("Mate 60 Pro"), "second name parsed");

        // 空负载 / 仅 \0
        check(BoxProtocolSession.parseBluetoothPairList("").isEmpty(), "empty pair list");
        check(BoxProtocolSession.parseBluetoothPairList("\0").isEmpty(), "nul-only pair list");
        check(BoxProtocolSession.parseBluetoothPairList(null).isEmpty(), "null pair list");

        // 无设备名时 toString 退化为 MAC
        BoxProtocolSession.PairedDevice nameless =
                BoxProtocolSession.parseBluetoothPairList("AA:BB:CC:DD:EE:FF\n").get(0);
        check(nameless.toString().equals("AA:BB:CC:DD:EE:FF"), "nameless device shows mac");

        // 写回格式与下发格式一致：每行「MAC + 名称 + \n」，整体以 \0 结尾
        java.util.List<BoxProtocolSession.PairedDevice> twoDevices = devices;
        byte[] writeBack = BoxProtocolSession.createPairedListPayload(twoDevices);
        String text = new String(writeBack, StandardCharsets.UTF_8);
        check(text.equals("AA:BB:CC:DD:EE:FFiPhone 15\n11:22:33:44:55:66Mate 60 Pro\n\0"),
                "pair list write-back matches read format");
        check(text.endsWith("\0"), "pair list write-back ends with nul");

        // 写回内容能被自己的解析器读回（往返一致）
        java.util.List<BoxProtocolSession.PairedDevice> roundTrip =
                BoxProtocolSession.parseBluetoothPairList(text);
        check(roundTrip.size() == 2, "write-back round-trips device count");
        check(roundTrip.get(1).getMac().equals("11:22:33:44:55:66"), "round-trip mac");
        check(roundTrip.get(1).getName().equals("Mate 60 Pro"), "round-trip name");

        // 空列表只发一个 \0；非法 MAC 的条目被跳过
        check(new String(BoxProtocolSession.createPairedListPayload(null), StandardCharsets.UTF_8)
                .equals("\0"), "empty pair list payload is single nul");
        check(new String(BoxProtocolSession.createPairedListPayload(
                BoxProtocolSession.parseBluetoothPairList("ZZ:BB:CC:DD:EE:FFbad\n")),
                StandardCharsets.UTF_8).equals("\0"), "invalid entries dropped on write-back");

        // 名称里的换行必须被替换，否则盒子会拆成两条记录
        BoxProtocolSession.PairedDevice injected =
                BoxProtocolSession.parseBluetoothPairList("AA:BB:CC:DD:EE:FFname\n").get(0);
        BoxProtocolSession.PairedDevice multiline = new BoxProtocolSession.PairedDevice(
                injected.getMac(), "car\r\nphone");
        String sanitized = new String(BoxProtocolSession.createPairedListPayload(
                java.util.Collections.singletonList(multiline)), StandardCharsets.UTF_8);
        check(sanitized.equals("AA:BB:CC:DD:EE:FFcar  phone\n\0"),
                "newlines in device name are flattened");
        check(BoxProtocolSession.parseBluetoothPairList(sanitized).size() == 1,
                "flattened name keeps a single record");
    }

    private static void testManualDisconnectFrame() {
        // cmd 15 是零负载命令：原版 len == 0 时直接跳过加密与 magic 切换
        byte[] frame = BoxProtocolSession.createManualDisconnectFrame();
        check(frame.length == 16, "manual disconnect frame carries only the 16-byte header");
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        check(header.getInt(0) == 0x55AA55AA, "manual disconnect frame uses legacy magic");
        check(header.getInt(4) == 0, "manual disconnect payload length is zero");
        check(header.getInt(8) == 15, "manual disconnect command is CMD_MANUAL_DISCONNECT");
        check(header.getInt(12) == ~15, "manual disconnect check is ~cmd");

        // 帧应能被解析回同一条命令，且负载长度为 0
        try {
            ProtocolFrame.Header decoded = ProtocolFrame.decodeHeader(frame, 0, 16 * 1024 * 1024);
            check(decoded.getCommand() == 15, "manual disconnect frame decodes to cmd 15");
            check(decoded.getPayloadLength() == 0, "manual disconnect frame decodes to empty payload");
            check(!decoded.isCryptFrame(), "manual disconnect frame stays plaintext");
        } catch (IOException exception) {
            throw new AssertionError("manual disconnect frame is not decodable", exception);
        }

        // 抑制窗口必须在手动断开后覆盖一次自动回连延迟，否则断开后立刻被连回来
        check(BoxProtocolSession.MANUAL_DISCONNECT_COOLDOWN_MS > 2000L,
                "manual disconnect cooldown outlasts the auto-connect delay");
    }

    private static void testBoxFirmwarePayload() throws Exception {
        // 固件下发：cmd 153 的 4+pathLen+4+dataLen 结构
        byte[] image = new byte[]{0x11, 0x22, 0x33, 0x44};
        byte[] payload = BoxProtocolSession.createBoxFilePayload(
                BoxProtocolSession.FIRMWARE_BOX_PATH, image);
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int pathLength = buffer.getInt(0);
        check(pathLength == BoxProtocolSession.FIRMWARE_BOX_PATH.length(),
                "firmware path length is written verbatim");
        byte[] path = new byte[pathLength];
        buffer.position(4);
        buffer.get(path);
        check(new String(path, StandardCharsets.UTF_8)
                        .equals(BoxProtocolSession.FIRMWARE_BOX_PATH),
                "firmware path round-trips");
        int dataLength = buffer.getInt(4 + pathLength);
        check(dataLength == image.length, "firmware data length matches");
        check(payload.length == 8 + pathLength + image.length,
                "firmware payload size is consistent");

        // 50MB 上限
        check(BoxProtocolSession.MAX_FIRMWARE_SIZE == 50 * 1024 * 1024,
                "firmware size cap matches original 50MB limit");

        // 187 状态码文案
        check(BoxProtocolSession.describeFirmwareStatus(1).equals("开始升级"), "187:1 start");
        check(BoxProtocolSession.describeFirmwareStatus(2).equals("升级成功"), "187:2 success");
        check(BoxProtocolSession.describeFirmwareStatus(3).equals("升级失败"), "187:3 failure");
        check(BoxProtocolSession.describeFirmwareStatus(5).equals("OTA 开始"), "187:5 ota start");
        check(BoxProtocolSession.describeFirmwareStatus(6).equals("OTA 成功"), "187:6 ota success");
        check(BoxProtocolSession.describeFirmwareStatus(7).equals("OTA 失败"), "187:7 ota failure");

        // 升级成功后主动重启：只有 2/6 需要下发 cmd 206
        check(BoxProtocolSession.shouldRebootAfterUpgrade(2), "187:2 triggers reboot");
        check(BoxProtocolSession.shouldRebootAfterUpgrade(6), "187:6 triggers reboot");
        check(!BoxProtocolSession.shouldRebootAfterUpgrade(1), "187:1 does not reboot");
        check(!BoxProtocolSession.shouldRebootAfterUpgrade(3), "187:3 does not reboot");
        check(!BoxProtocolSession.shouldRebootAfterUpgrade(5), "187:5 does not reboot");
        check(!BoxProtocolSession.shouldRebootAfterUpgrade(7), "187:7 does not reboot");
        check(!BoxProtocolSession.shouldRebootAfterUpgrade(0), "187:0 does not reboot");

        // cmd 206 帧必须是零负载 + 明文 magic，与原版 length==0 提前返回的分支一致
        byte[] rebootFrame = BoxProtocolSession.createRebootFrame();
        check(rebootFrame.length == 16, "reboot frame carries only the 16-byte header");
        ByteBuffer reboot = ByteBuffer.wrap(rebootFrame).order(ByteOrder.LITTLE_ENDIAN);
        check(reboot.getInt(0) == 0x55AA55AA, "reboot frame uses legacy magic");
        check(reboot.getInt(4) == 0, "reboot frame payload length is zero");
        check(reboot.getInt(8) == 206, "reboot frame command is CMD_BOX_REBOOT");
        check(reboot.getInt(12) == ~206, "reboot frame check is ~cmd");

        // checkBox 表单：version 字典序比较即时间序
        check(BoxFirmwareChecker.compareVersion("2025.03.20.1130", "2024.07.08.0000") > 0,
                "newer box version sorts after older");
        check(BoxFirmwareChecker.compareVersion("2024.07.08.0000", "2024.07.08.0000") == 0,
                "equal versions compare equal");

        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("code", "36");
        form.put("ver", "2024.07.08.0000");
        form.put("mfd", "manstep");
        String encoded = BoxFirmwareChecker.buildForm(form);
        check(encoded.equals("code=36&ver=2024.07.08.0000&mfd=manstep"),
                "checkBox form encodes in insertion order without escaping dots");
        form.clear();
        form.put("model", "A B");
        check(BoxFirmwareChecker.buildForm(form).equals("model=A+B"),
                "form encodes spaces as plus");
    }

    private enum LightStatus { LIGHT_OPEN, LIGHT_CLOSE, LIGHT_INVALID }

    private static void testHeadlightNightMode() {
        check(HeadlightNightMode.settingForStatus(LightStatus.LIGHT_OPEN, -1) == 1,
                "headlights on enables night mode");
        check(HeadlightNightMode.settingForStatus(LightStatus.LIGHT_CLOSE, -1) == 0,
                "headlights off disables night mode");
        for (int previous : new int[]{-1, 0, 1}) {
            check(HeadlightNightMode.settingForStatus(null, previous) == previous,
                    "null light status retains last known state");
            check(HeadlightNightMode.settingForStatus(LightStatus.LIGHT_INVALID, previous) == previous,
                    "invalid light status retains last known state");
        }
        check(HeadlightNightMode.settingForStatus("LIGHT_CLOSE", 1) == 1,
                "unexpected status type must not turn night mode off");
        check(HeadlightNightMode.settingForStatus(LightStatus.LIGHT_CLOSE,
                HeadlightNightMode.settingForStatus(LightStatus.LIGHT_INVALID,
                        HeadlightNightMode.settingForStatus(LightStatus.LIGHT_OPEN, -1))) == 0,
                "valid light changes resume after unknown status");
    }

    private static void testOriginalVideoOptions() {
        check(VideoOptions.DEFAULT_FPS == 30, "original regular default is 30fps");
        check(VideoOptions.defaultFrameRate("Freescale-SABRESD-MX6DQ-4.4.2(19)", "") == 60,
                "original Freescale default override is retained");
        check(VideoOptions.defaultFrameRate("", "HUAWEI-ICHU-X1EV-ICHU-X1EV-10(29)") == 60,
                "original Huawei default override is retained");
        check(VideoOptions.defaultFrameRate("generic", "generic") == 30, "generic original frame rate");
        check(Arrays.equals(VideoOptions.FRAME_RATES, new int[]{60, 55, 50, 45, 40, 35, 30, 25, 20}),
                "original main-screen frame-rate choices");
        for (int rate : VideoOptions.FRAME_RATES) {
            check(VideoOptions.effectiveFrameRate(VideoOptions.HARDWARE, rate) == rate,
                    "hardware uses selected frame rate");
            int effective = VideoOptions.effectiveFrameRate(VideoOptions.SOFTWARE, rate);
            check(effective == 25, "native software negotiates fixed 25fps");
            ByteBuffer open = ByteBuffer.wrap(BoxOpenRequest.createPayload(1280, 720, effective, 17))
                    .order(ByteOrder.LITTLE_ENDIAN);
            check(open.getInt(8) == 25, "software frame rate must reach the wire");
        }
        check(VideoOptions.migrateFrameRate(0) == 30 && VideoOptions.migrateFrameRate(1) == 60,
                "old UI indices convert to actual fps rather than 0 or 1fps");
        check(VideoOptions.migrateDecoder(0) == VideoOptions.HARDWARE
                        && VideoOptions.migrateDecoder(2) == VideoOptions.SOFTWARE
                        && VideoOptions.migrateDecoder(3) == VideoOptions.SOFTWARE,
                "legacy auto/platform-software choices map to original hardware/native software");
        check(VideoOptions.resolutions(800, 480).equals(Arrays.asList(
                        "800x480", "840x504", "882x528", "920x552")),
                "original 800x480 resolution scales and ceil-even rounding");
        check(VideoOptions.resolutions(1280, 720).contains("1088x768"),
                "original 720-line special resolution");
        check(VideoOptions.resolutions(1920, 1080).contains("1920x1152"),
                "original 1080-line special resolution");
        check(VideoOptions.resolutions(1080, 1920).get(0).equals("1080x1920"),
                "original portrait canvas is not transposed to landscape");
        check(VideoOptions.automaticDpi(1280, 720) == 192
                        && VideoOptions.automaticDpi(720, 1280) == 160,
                "original integer/float automatic DPI formula");
        for (String value : VideoOptions.resolutions(1920, 1080)) {
            int[] size = VideoOptions.parseSize(value);
            check((size[0] & 1) == 0 && (size[1] & 1) == 0, "resolution choices are even");
        }
        expectIllegalArgument(() -> VideoOptions.parseSize("1281x720"));
        expectIllegalArgument(() -> VideoOptions.parseSize("not-a-size"));
        expectIllegalArgument(() -> VideoOptions.effectiveFrameRate(VideoOptions.HARDWARE, 1));
    }

    private static void testVideoFrameQueue() throws IOException {
        byte[] payload = new byte[25];
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(1280).putInt(720);
        VideoFrame a = VideoFrame.parse(payload, false);
        VideoFrame b = VideoFrame.parse(payload, false);
        VideoFrame c = VideoFrame.parse(payload, false);
        VideoFrame d = VideoFrame.parse(payload, false);
        VideoFrameQueue queue = new VideoFrameQueue(2);
        check(queue.offer(a) && queue.offer(b), "video frames queue without dropping");
        VideoFrameQueue.Entry pending = queue.poll();
        check(pending.frame == a, "decoder retains the oldest pending input");
        check(queue.offer(c), "new input must follow pending input");
        check(queue.poll().frame == b && queue.poll().frame == c,
                "backpressure must not requeue oldest input behind newer reference frames");
        check(queue.offer(a) && queue.offer(b) && !queue.offer(d),
                "overflow must be explicitly reported");
        check(queue.generation() != pending.generation,
                "overflow invalidates even an input frame held outside the queue");
        VideoFrameQueue.Entry recovered = queue.poll();
        check(recovered.frame == d && recovered.generation == queue.generation()
                        && queue.poll() == null,
                "overflow clears the broken dependency chain, not just one reference frame");
        long beforeReset = queue.generation();
        queue.clear();
        check(queue.generation() != beforeReset && queue.poll() == null,
                "decoder errors invalidate pending input and queued frames");
        expectIllegalArgument(() -> new VideoFrameQueue(0));
    }

    private static void testVendorKeys() {
        VendorKeys keys = new VendorKeys();
        check(keys.press("NEXT", "DOWN", 1000), "vendor press");
        check(!keys.press("NEXT", "DOWN", 1100), "vendor repeat ignored");
        check(!keys.press("NEXT", "UP", 1200), "release must not execute");
        check(keys.press("NEXT", "DOWN", 1300), "second press works");
        check(!keys.press("VR", "LONG_EVENT", 1400), "long states ignored");
        check(!keys.press("UNKNOWN", "DOWN", 1400), "unknown vendor key ignored");
        check(keys.press("MAIN", "DOWN", 2000), "touch main needs no up");
        check(!keys.press("MAIN", "DOWN", 2100), "touch debounce");
        check(keys.press("MAIN", "DOWN", 2400), "next touch without up");
        keys.reset();
        check(keys.press("NEXT", "DOWN", 2500), "pause clears held keys");
    }

    private static void testVideoGeometry() {
        check(Arrays.equals(VideoGeometry.fit(2350, 1080, 1080, 2350),
                new int[]{496, 1080}), "portrait stream must not stretch on landscape display");
        check(Arrays.equals(VideoGeometry.fit(2350, 1080, 1920, 1080),
                new int[]{1920, 1080}), "16:9 stream is pillarboxed on wide phone");
        check(Arrays.equals(VideoGeometry.fit(1080, 2350, 2350, 1080),
                new int[]{1080, 496}), "landscape session survives portrait rotation");
        check(Arrays.equals(VideoGeometry.fit(2350, 1080, 2350, 1080),
                new int[]{2350, 1080}), "matching stream fills display");
        check(Arrays.equals(VideoGeometry.fit(1080, 235, 1920, 1080),
                new int[]{418, 235}), "very short landscape viewport preserves aspect ratio");
        expectIllegalArgument(() -> VideoGeometry.fit(0, 1080, 1920, 1080));
    }

    private static void testBoxSettings() {
        BoxSetting drive = BoxSetting.integer("drivePosition", 1);
        check(drive.command == 25, "drive settings command");
        check(new String(drive.payload(), StandardCharsets.UTF_8).equals("{\"drivePosition\":1}"),
                "drive settings JSON");
        check(BoxSetting.integer("UseBTPhone", 1).command == 162, "Bluetooth phone config command");
        check(BoxSetting.name(true, "123456789012345").command == 13, "Bluetooth name command");
        check(BoxSetting.name(false, "AutoPlay").command == 14, "Wi-Fi name command");
        check(BoxSetting.name(true, "汽车汽车汽").payload().length == 15, "UTF-8 name length");
        expectIllegalArgument(() -> BoxSetting.name(false, "汽车汽车汽车"));
        expectIllegalArgument(() -> BoxSetting.name(true, ""));
        expectIllegalArgument(() -> BoxSetting.name(true, "abc\n"));
        expectIllegalArgument(() -> BoxSetting.integer("unknown", 1));
        expectIllegalArgument(() -> BoxSetting.integer("DockPosition", 3));
        expectIllegalArgument(() -> BoxSetting.integer("drivePosition", -1));
        expectIllegalArgument(() -> BoxSetting.integer("mediaDelay", 2001));
        check(BoxSetting.wifiBand(true).command == 8, "Wi-Fi band command");
        check(ByteBuffer.wrap(BoxSetting.wifiBand(true).payload()).order(ByteOrder.LITTLE_ENDIAN)
                .getInt() == 25, "5 GHz subcommand");
        check(ByteBuffer.wrap(BoxSetting.wifiBand(false).payload()).order(ByteOrder.LITTLE_ENDIAN)
                .getInt() == 24, "2.4 GHz subcommand");
        check(ByteBuffer.wrap(BoxSetting.audioTransfer(true).payload()).order(ByteOrder.LITTLE_ENDIAN)
                .getInt() == 22, "audio transfer subcommand");
        BoxSetting dpi = BoxSetting.screenDpi(240);
        check(dpi.command == 153, "DPI upload command");
        ByteBuffer upload = ByteBuffer.wrap(dpi.payload()).order(ByteOrder.LITTLE_ENDIAN);
        byte[] path = new byte[upload.getInt()];
        upload.get(path);
        check(new String(path, StandardCharsets.UTF_8).equals("/tmp/screen_dpi\0"), "DPI upload path");
        check(upload.getInt() == 4 && upload.getInt() == 240 && !upload.hasRemaining(), "DPI upload shape");
        byte[] modified = dpi.payload();
        modified[0] = 0;
        check(dpi.payload()[0] != 0, "settings payload defensive copy");
    }

    private static void testLegacyEncodeAndDecode() throws IOException {
        byte[] payload = {1, 2, 3, 4};
        byte[] frame = ProtocolFrame.encodeLegacy(1, payload);
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        check(header.getInt() == ProtocolFrame.LEGACY_MAGIC, "legacy magic");
        check(header.getInt() == payload.length, "payload length");
        check(header.getInt() == 1, "command");
        check(header.getInt() == ~1, "command check");
        check(Arrays.equals(Arrays.copyOfRange(frame, ProtocolFrame.HEADER_SIZE, frame.length), payload), "payload bytes");

        ProtocolFrame.Header decoded = ProtocolFrame.decodeHeader(frame, 0, 1024);
        check(decoded.getCommand() == 1, "decoded command");
        check(decoded.getPayloadLength() == payload.length, "decoded length");
        check(!decoded.isCryptFrame(), "legacy frame mode");
    }

    private static void testCryptHeaderRecognition() throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrame.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(ProtocolFrame.CRYPT_MAGIC);
        bytes.putInt(0);
        bytes.putInt(170);
        bytes.putInt(~170);
        ProtocolFrame.Header decoded = ProtocolFrame.decodeHeader(bytes.array(), 0, 1024);
        check(decoded.isCryptFrame(), "crypt frame mode");
    }

    private static void testCarPlayOpenPayload() {
        byte[] payload = BoxOpenRequest.createPayload(1920, 1080, 255);
        ByteBuffer open = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        check(open.getInt(0) == 1920, "open width");
        check(open.getInt(4) == 1080, "open height");
        check(open.getInt(8) == 30, "original default open frame rate");
        check(open.getInt(20) == 255, "box version code");
        check(open.getInt(24) == 2, "CarPlay mode");
        check(BoxOpenRequest.versionCodeFallback("2021.12.22.1647CMP") == 255, "P firmware fallback");
        check(BoxOpenRequest.versionCodeFallback(null) == 0, "missing box firmware fallback");
        check(BoxOpenRequest.versionCodeFallback("2021.12.22.1647CMPX") == 0, "unknown box suffix fallback");

        byte[] customFrameRate = BoxOpenRequest.createPayload(1280, 720, 30, 17);
        check(ByteBuffer.wrap(customFrameRate).order(ByteOrder.LITTLE_ENDIAN).getInt(8) == 30,
            "custom open frame rate");
        expectIllegalArgument(() -> BoxOpenRequest.createPayload(1280, 720, 61, 17));
    }

    private static void testVideoFrameParsing() throws Exception {
        byte[] payload = ByteBuffer.allocate(VideoFrame.METADATA_SIZE + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1080)
                .putInt(2352)
                .putInt(1)
                .putLong(987654321L)
                .put(new byte[]{0, 0, 0, 1})
                .array();
        VideoFrame frame = VideoFrame.parse(payload, true);
        check(frame.getWidth() == 1080, "video width");
        check(frame.getHeight() == 2352, "video height");
        check(frame.getPresentationTimeUs() == 987654321L, "video timestamp");
        check(Arrays.equals(frame.getH264Data(), new byte[]{0, 0, 0, 1}), "video payload");

        expectIOException(() -> VideoFrame.parse(new byte[VideoFrame.METADATA_SIZE], false));
    }

    private static void testDashboardFrameParsing() throws Exception {
        // 副屏帧：20 字节头 + H.264，时间戳只有 4 字节毫秒（偏移 12）
        byte[] payload = ByteBuffer.allocate(VideoFrame.METADATA_SIZE + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(800)
                .putInt(480)
                .putInt(2)
                .putInt(1234)
                .putInt(0)                 // 偏移 16 的保留字节
                .put(new byte[]{0, 0, 0, 1})
                .array();
        VideoFrame frame = VideoFrame.parseDashboard(payload);
        check(frame.getWidth() == 800, "dashboard width");
        check(frame.getHeight() == 480, "dashboard height");
        check(frame.getFlags() == 2, "dashboard flags");
        check(frame.getPresentationTimeUs() == 1234_000L, "dashboard ms timestamp to us");
        check(Arrays.equals(frame.getH264Data(), new byte[]{0, 0, 0, 1}), "dashboard payload offset");

        // 时间戳为 0 时按未知处理，交给解码器自己生成 pts
        byte[] noTimestamp = ByteBuffer.allocate(VideoFrame.METADATA_SIZE + 1)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(640).putInt(360).putInt(0).putInt(0).putInt(0)
                .put((byte) 0x65).array();
        check(VideoFrame.parseDashboard(noTimestamp).getPresentationTimeUs() == 0L,
                "dashboard zero timestamp stays unknown");

        // 头部不完整 / 尺寸非法都要拒绝
        expectIOException(() -> VideoFrame.parseDashboard(new byte[VideoFrame.METADATA_SIZE]));
        byte[] badSize = ByteBuffer.allocate(VideoFrame.METADATA_SIZE + 1)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(480).putInt(0).putInt(0).putInt(0)
                .put((byte) 0x65).array();
        expectIOException(() -> VideoFrame.parseDashboard(badSize));
        expectIOException(() -> VideoFrame.parseDashboard(null));

        // naviScreenInfo：盒子侧副屏画布尺寸
        byte[] info = BoxSetting.naviScreenInfo(800, 480, 30, 160).payload();
        check(BoxSetting.naviScreenInfo(800, 480, 30, 160).command == 25,
                "naviScreenInfo uses CMD_BOX_SETTINGS");
        String json = new String(info, StandardCharsets.UTF_8);
        check(json.contains("\"naviScreenInfo\""), "naviScreenInfo key present");
        check(json.contains("\"width\":800"), "naviScreenInfo width");
        check(json.contains("\"height\":480"), "naviScreenInfo height");
        check(json.contains("\"safearea\""), "naviScreenInfo safearea");
        check(json.contains("\"fps\":30"), "naviScreenInfo fps");
        // 非法 fps 回落到 30，非法尺寸直接拒绝
        check(new String(BoxSetting.naviScreenInfo(640, 360, 90, 0).payload(), StandardCharsets.UTF_8)
                .contains("\"fps\":30"), "naviScreenInfo fps clamped");
        boolean threw = false;
        try {
            BoxSetting.naviScreenInfo(0, 360, 30, 160);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "naviScreenInfo rejects zero width");
    }

    private static void testAudioFrameParsing() throws Exception {
        byte[] payload = ByteBuffer.allocate(AudioFrame.HEADER_SIZE + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4)
                .putFloat(0.75f)
                .putInt(2)
                .put(new byte[]{1, 2, 3, 4})
                .array();
        AudioFrame frame = AudioFrame.parse(payload);
        check(frame.getDecodeType() == 4, "audio decode type");
        check(frame.getAudioType() == 2, "audio stream type");
        check(frame.getSampleRate() == 48000, "audio sample rate");
        check(frame.getChannelCount() == 2, "audio channels");
        check(frame.getPcmData().length == 4, "audio payload offset");
            check(AudioFrame.sampleRateForDecodeType(3) == 8000, "microphone sample rate mapping");
            check(AudioFrame.channelCountForDecodeType(5) == 1, "microphone channel mapping");
        expectIOException(() -> AudioFrame.parse(new byte[AudioFrame.HEADER_SIZE]));
    }

    private static void testTouchFrameEncoding() {
        byte[] payload = TouchFrame.encode(new float[]{0.25f}, new float[]{0.75f}, new int[]{2}, 1);
        ByteBuffer point = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        check(payload.length == TouchFrame.POINT_SIZE, "touch point length");
        check(point.getFloat(0) == 0.25f, "touch x");
        check(point.getFloat(4) == 0.75f, "touch y");
        check(point.getInt(8) == 1, "touch pressed state");
        check(point.getInt(12) == 2, "touch pointer id");
        byte[] move = TouchFrame.encode(new float[]{0.5f}, new float[]{0.6f}, new int[]{1}, 2);
        check(ByteBuffer.wrap(move).order(ByteOrder.LITTLE_ENDIAN).getInt(8) == 2, "touch move state");
        byte[] clamped = TouchFrame.encode(new float[]{1.2f}, new float[]{-0.2f}, new int[]{0}, 0);
        ByteBuffer clampedPoint = ByteBuffer.wrap(clamped).order(ByteOrder.LITTLE_ENDIAN);
        check(clampedPoint.getFloat(0) == 1.0f, "touch x clamp");
        check(clampedPoint.getFloat(4) == 0.0f, "touch y clamp");
    }

    private static void testCarLogoPayloadEncoding() {
        String boxPath = "/etc/icon_120x120.png\0";
        byte[] pathBytes = boxPath.getBytes(StandardCharsets.UTF_8);
        byte[] imageBytes = {11, 22, 33, 44};
        byte[] payload = BoxProtocolSession.createBoxFilePayload(boxPath, imageBytes);
        ByteBuffer file = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        check(file.getInt() == pathBytes.length, "car logo path length");
        byte[] encodedPath = new byte[pathBytes.length];
        file.get(encodedPath);
        check(Arrays.equals(encodedPath, pathBytes), "car logo target path");
        check(file.getInt() == imageBytes.length, "car logo image length");
        byte[] encodedImage = new byte[imageBytes.length];
        file.get(encodedImage);
        check(Arrays.equals(encodedImage, imageBytes), "car logo image bytes");

        ByteBuffer brand = ByteBuffer.wrap(BoxProtocolSession.createCarBrandPayload(7))
                .order(ByteOrder.LITTLE_ENDIAN);
        check(brand.getInt() == 7, "car brand id");
        check(brand.getInt() == 0, "car brand reserved field");
    }

    private static void testRejectsInvalidHeaders() throws Exception {
        expectIOException(() -> ProtocolFrame.decodeHeader(new byte[8], 0, 1024));

        byte[] badCommandCheck = ProtocolFrame.encodeLegacy(2, new byte[0]);
        badCommandCheck[12] ^= 1;
        expectIOException(() -> ProtocolFrame.decodeHeader(badCommandCheck, 0, 1024));

        byte[] oversized = ProtocolFrame.encodeLegacy(2, new byte[0]);
        ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 2048);
        expectIOException(() -> ProtocolFrame.decodeHeader(oversized, 0, 1024));
    }

    private static void expectIOException(IoAction action) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected IOException");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private interface IoAction {
        void run() throws Exception;
    }
}
