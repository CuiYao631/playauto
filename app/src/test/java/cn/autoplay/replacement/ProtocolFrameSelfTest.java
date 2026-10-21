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
        testVideoFrameQueue();
        testAudioFrameParsing();
        testTouchFrameEncoding();
        testCarLogoPayloadEncoding();
        testRejectsInvalidHeaders();
        testBoxSettings();
        testVideoGeometry();
        testOriginalVideoOptions();
        testVendorKeys();
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
