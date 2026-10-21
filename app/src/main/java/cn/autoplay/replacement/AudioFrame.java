package cn.autoplay.replacement;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class AudioFrame {
    static final int HEADER_SIZE = 12;
    private final int decodeType;
    private final int audioType;
    private final float volume;
    private final byte[] pcmData;

    private AudioFrame(int decodeType, int audioType, float volume, byte[] pcmData) {
        this.decodeType = decodeType;
        this.audioType = audioType;
        this.volume = volume;
        this.pcmData = pcmData;
    }

    public static AudioFrame parse(byte[] payload) throws IOException {
        if (payload == null || payload.length <= HEADER_SIZE) {
            throw new IOException("Audio packet has no PCM payload");
        }
        ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int decodeType = header.getInt(0);
        float volume = header.getFloat(4);
        int audioType = header.getInt(8);
        if (decodeType < 1 || decodeType > 7) {
            throw new IOException("Unsupported audio decode type: " + decodeType);
        }
        if (Float.isNaN(volume) || Float.isInfinite(volume) || audioType < 0) {
            throw new IOException("Invalid audio stream metadata");
        }
        int dataLength = payload.length - HEADER_SIZE;
        if ((dataLength & 1) != 0) {
            throw new IOException("PCM16 payload length must be even");
        }
        return new AudioFrame(decodeType, audioType, volume,
                Arrays.copyOfRange(payload, HEADER_SIZE, payload.length));
    }

    public int getDecodeType() {
        return decodeType;
    }

    public int getAudioType() {
        return audioType;
    }

    public float getVolume() {
        return volume;
    }

    public byte[] getPcmData() {
        return pcmData;
    }

    public int getSampleRate() {
        return sampleRateForDecodeType(decodeType);
    }

    static int sampleRateForDecodeType(int decodeType) {
        switch (decodeType) {
            case 1:
            case 2:
                return 44100;
            case 3:
                return 8000;
            case 4:
                return 48000;
            case 6:
                return 24000;
            case 5:
            case 7:
                return 16000;
            default:
                throw new IllegalArgumentException("Unsupported audio decode type: " + decodeType);
        }
    }

    public int getChannelCount() {
        return channelCountForDecodeType(decodeType);
    }

    static int channelCountForDecodeType(int decodeType) {
        switch (decodeType) {
            case 1:
            case 2:
            case 4:
            case 7:
                return 2;
            case 3:
            case 5:
            case 6:
                return 1;
            default:
                throw new IllegalArgumentException("Unsupported audio decode type: " + decodeType);
        }
    }
}
