package cn.autoplay.replacement;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.IOException;

public final class AudioCapture implements AutoCloseable {
    public interface FrameSink {
        void onPcmFrame(int decodeType, byte[] pcmData) throws IOException;
    }

    public interface Listener {
        void onCaptureError(String error);
    }

    private final Listener listener;
    private volatile boolean running;
    private volatile AudioRecord recorder;
    private volatile float gain = 1.0f;
    private Thread captureThread;

    public AudioCapture(Listener listener) {
        this.listener = listener;
    }

    public void setGainDb(int decibels) {
        gain = (float) Math.pow(10.0, decibels / 20.0);
    }

    private void applyGain(byte[] buffer, int count) {
        float factor = gain;
        if (factor == 1.0f) {
            return;
        }
        for (int i = 0; i + 1 < count; i += 2) {
            int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            int amplified = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * factor)));
            buffer[i] = (byte) amplified;
            buffer[i + 1] = (byte) (amplified >> 8);
        }
    }

    public synchronized void start(int decodeType, FrameSink sink) throws IOException {
        stop();
        int sampleRate = AudioFrame.sampleRateForDecodeType(decodeType);
        int channelCount = AudioFrame.channelCountForDecodeType(decodeType);
        int channelMask = channelCount == 1
                ? AudioFormat.CHANNEL_IN_MONO
                : AudioFormat.CHANNEL_IN_STEREO;
        int minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            throw new IOException("Unsupported microphone format: " + sampleRate + "Hz/" + channelCount + "ch");
        }

        int frameBytes = sampleRate * channelCount * 2 / 50;
        int bufferSize = Math.max(minBuffer * 2, frameBytes * 2);
        AudioRecord newRecorder;
        try {
            newRecorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
        } catch (SecurityException exception) {
            throw new IOException("Microphone permission denied", exception);
        }
        if (newRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
            newRecorder.release();
            throw new IOException("Android AudioRecord initialization failed");
        }

        try {
            newRecorder.startRecording();
        } catch (SecurityException exception) {
            newRecorder.release();
            throw new IOException("Microphone recording permission denied", exception);
        }
        if (newRecorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            newRecorder.release();
            throw new IOException("Android microphone did not enter recording state");
        }

        recorder = newRecorder;
        running = true;
        captureThread = new Thread(() -> captureLoop(decodeType, frameBytes, sink), "autoplay-microphone");
        captureThread.start();
    }

    private void captureLoop(int decodeType, int frameBytes, FrameSink sink) {
        AudioRecord activeRecorder = recorder;
        if (activeRecorder == null) {
            return;
        }
        byte[] buffer = new byte[frameBytes];
        while (running) {
            int count = activeRecorder.read(buffer, 0, buffer.length);
            if (!running) {
                break;
            }
            if (count < 0) {
                reportError("AudioRecord read failed: " + count);
                break;
            }
            if (count == 0) {
                continue;
            }
            if ((count & 1) != 0) {
                reportError("AudioRecord returned an unaligned PCM16 frame");
                break;
            }
            applyGain(buffer, count);
            byte[] pcm = new byte[count];
            System.arraycopy(buffer, 0, pcm, 0, count);
            try {
                sink.onPcmFrame(decodeType, pcm);
            } catch (IOException exception) {
                reportError(exception.getMessage());
                break;
            }
        }
        running = false;
    }

    private void reportError(String error) {
        if (listener != null && running) {
            listener.onCaptureError(error);
        }
    }

    public synchronized void stop() {
        running = false;
        AudioRecord activeRecorder = recorder;
        recorder = null;
        if (activeRecorder != null) {
            try {
                if (activeRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    activeRecorder.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            activeRecorder.release();
        }
        Thread activeThread = captureThread;
        captureThread = null;
        if (activeThread != null && activeThread != Thread.currentThread()) {
            activeThread.interrupt();
            try {
                activeThread.join(300L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }
}
