package cn.autoplay.replacement;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cn.manstep.phonemirrorBox.BoxInterface.BoxCore;

public final class ProtocolFrame {
    public static final int HEADER_SIZE = 16;
    public static final int LEGACY_MAGIC = 0x55AA55AA;
    public static final int CRYPT_MAGIC = 0x55BB55BB;

    private ProtocolFrame() {
    }

    public static byte[] encodeLegacy(int command, byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        return encodeWithMagic(command, payload, LEGACY_MAGIC);
    }

    public static byte[] encode(int command, byte[] payload, boolean crypt, int nonce) throws IOException {
        if (!crypt) {
            return encodeLegacy(command, payload);
        }
        requireNativeCrypt();
        byte[] encryptedPayload = payload.clone();
        BoxCore.stCheck(true, encryptedPayload, nonce, encryptedPayload);
        return encodeWithMagic(command, encryptedPayload, CRYPT_MAGIC);
    }

    public static byte[] decodePayload(Header header, byte[] payload, int nonce) throws IOException {
        if (!header.isCryptFrame()) {
            return payload.clone();
        }
        requireNativeCrypt();
        byte[] encryptedInput = payload.clone();
        byte[] decodedOutput = payload.clone();
        BoxCore.stCheck(false, encryptedInput, nonce, decodedOutput);
        return decodedOutput;
    }

    /**
     * 加密帧必须走 {@code libnx1}，而该库在重签名构建里会直接崩溃（见 {@link CryptoSupport}）。
     *
     * <p>这里做最后一道防线：探测结论为不可用时直接抛出带说明的异常，
     * 交给会话层按「不支持加密」处理，绝不在主进程里调用 native。
     */
    private static void requireNativeCrypt() throws IOException {
        if (!BoxCore.isAvailable()) {
            throw new IOException("libnx1 未加载，无法处理加密帧");
        }
        if (!CryptoSupport.isUsable()) {
            throw new IOException("原生加密库不可用（应用签名与原版不一致），已跳过加密");
        }
    }

    private static byte[] encodeWithMagic(int command, byte[] payload, int magic) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        ByteBuffer frame = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(magic);
        frame.putInt(payload.length);
        frame.putInt(command);
        frame.putInt(~command);
        frame.put(payload);
        return frame.array();
    }

    public static Header decodeHeader(byte[] bytes, int offset, int maximumPayloadLength) throws IOException {
        if (bytes == null || offset < 0 || maximumPayloadLength < 0
                || offset > bytes.length - HEADER_SIZE) {
            throw new IOException("Incomplete protocol header");
        }

        ByteBuffer header = ByteBuffer.wrap(bytes, offset, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        int magic = header.getInt();
        int payloadLength = header.getInt();
        int command = header.getInt();
        int commandCheck = header.getInt();

        if (magic != LEGACY_MAGIC && magic != CRYPT_MAGIC) {
            throw new IOException(String.format("Unknown protocol magic: 0x%08X", magic));
        }
        if (payloadLength < 0 || payloadLength > maximumPayloadLength) {
            throw new IOException("Protocol payload length is out of range: " + payloadLength);
        }
        if (commandCheck != ~command) {
            throw new IOException("Protocol command check does not match");
        }

        return new Header(magic, payloadLength, command);
    }

    public static final class Header {
        private final int magic;
        private final int payloadLength;
        private final int command;

        private Header(int magic, int payloadLength, int command) {
            this.magic = magic;
            this.payloadLength = payloadLength;
            this.command = command;
        }

        public int getMagic() {
            return magic;
        }

        public int getPayloadLength() {
            return payloadLength;
        }

        public int getCommand() {
            return command;
        }

        public boolean isCryptFrame() {
            return magic == CRYPT_MAGIC;
        }
    }
}
