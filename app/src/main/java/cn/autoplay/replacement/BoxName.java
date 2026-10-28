package cn.autoplay.replacement;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class BoxName {
    static String decode(byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > 16) {
            throw new IOException("盒子名称报文长度无效：" + payload.length);
        }
        int end = payload.length;
        while (end > 0 && payload[end - 1] == 0) end--;
        if (end == 0 || end > 15) throw new IOException("盒子名称必须为 1 至 15 个 UTF-8 字节");
        String name;
        try {
            name = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload, 0, end)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("盒子名称不是有效 UTF-8", exception);
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IOException("盒子名称包含控制字符");
            }
        }
        return name;
    }
}
