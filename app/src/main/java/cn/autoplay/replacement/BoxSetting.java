package cn.autoplay.replacement;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class BoxSetting {
    final int command;
    private final byte[] payload;

    private BoxSetting(int command, byte[] payload) {
        this.command = command;
        this.payload = payload;
    }

    byte[] payload() {
        return payload.clone();
    }

    static BoxSetting integer(String key, int value) {
        boolean valid;
        switch (key) {
            case "drivePosition":
            case "UseBTPhone":
                valid = value == 0 || value == 1;
                break;
            case "DockPosition":
                valid = value >= 0 && value <= 2;
                break;
            case "mediaDelay":
                valid = value >= 0 && value <= 2000;
                break;
            default:
                throw new IllegalArgumentException("Unknown box setting: " + key);
        }
        if (!valid) {
            throw new IllegalArgumentException("Invalid " + key + ": " + value);
        }
        return new BoxSetting(key.equals("UseBTPhone") ? 162 : 25, ("{\"" + key + "\":" + value + "}")
                .getBytes(StandardCharsets.UTF_8));
    }

    static BoxSetting name(boolean bluetooth, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 15) {
            throw new IllegalArgumentException("名称必须为 1 至 15 个 UTF-8 字节");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("名称不能含有控制字符");
            }
        }
        return new BoxSetting(bluetooth ? 13 : 14, bytes);
    }

    static BoxSetting screenDpi(int dpi) {
        if (dpi < 80 || dpi > 480) {
            throw new IllegalArgumentException("DPI must be between 80 and 480");
        }
        byte[] path = "/tmp/screen_dpi\0".getBytes(StandardCharsets.UTF_8);
        return new BoxSetting(153, ByteBuffer.allocate(path.length + 12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(path.length).put(path).putInt(4).putInt(dpi).array());
    }

    static BoxSetting wifiBand(boolean fiveGhz) {
        return carPlay(fiveGhz ? 25 : 24);
    }

    static BoxSetting audioTransfer(boolean enabled) {
        return carPlay(enabled ? 22 : 23);
    }

    private static BoxSetting carPlay(int command) {
        return new BoxSetting(8, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).array());
    }
}
