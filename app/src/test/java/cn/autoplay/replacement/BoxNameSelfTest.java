package cn.autoplay.replacement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class BoxNameSelfTest {
    public static void main(String[] args) throws Exception {
        check("AutoBox".equals(BoxName.decode("AutoBox\0".getBytes(StandardCharsets.UTF_8))));
        check("AutoBox".equals(BoxName.decode("AutoBox".getBytes(StandardCharsets.UTF_8))));
        check("123456789012345".equals(BoxName.decode(
                "123456789012345\0".getBytes(StandardCharsets.UTF_8))));
        for (byte[] invalid : new byte[][]{new byte[0], new byte[17], {0},
                {(byte) 0xC3, 0x28}, {'a', 0, 'b'},
                "1234567890123456".getBytes(StandardCharsets.UTF_8)}) {
            try {
                BoxName.decode(invalid);
                throw new AssertionError("Invalid name accepted");
            } catch (IOException expected) {
                // Invalid packets must be surfaced by the protocol listener.
            }
        }
        System.out.println("BoxNameSelfTest passed");
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Decoded name mismatch");
    }
}
