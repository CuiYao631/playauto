package cn.autoplay.replacement;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class AudioPlayback implements AutoCloseable {
    public interface Listener {
        void onAudioError(String error);
    }

    private final Map<Integer, AudioTrack> tracks = new HashMap<>();
    private final Listener listener;
    private volatile float outputGain = 1.0f;
    private volatile float navigationMediaGain = 1.0f;
    private final ThreadPoolExecutor audioExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(12),
            runnable -> {
                Thread thread = new Thread(runnable, "autoplay-audio-playback");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile boolean closed;

    public AudioPlayback(Listener listener) {
        this.listener = listener;
    }

    public void submit(AudioFrame frame) {
        if (closed || frame == null) {
            return;
        }
        try {
            audioExecutor.execute(() -> play(frame));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    public void setOutputGain(float gain) {
        outputGain = Math.max(0.0f, Math.min(gain, 1.0f));
        if (closed) {
            return;
        }
        try {
            audioExecutor.execute(() -> {
                updateTrackVolumes();
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    public void setNavigationMediaGain(float gain) {
        if (Float.isNaN(gain) || Float.isInfinite(gain) || gain < 0 || gain > 1) {
            throw new IllegalArgumentException("Navigation media gain must be between 0 and 1");
        }
        navigationMediaGain = gain;
        if (!closed) {
            try {
                audioExecutor.execute(this::updateTrackVolumes);
            } catch (java.util.concurrent.RejectedExecutionException exception) {
                if (listener != null) {
                    listener.onAudioError("无法更新导航混音：" + exception.getMessage());
                }
            }
        }
    }

    private void updateTrackVolumes() {
        boolean alternateActive = false;
        for (Integer key : tracks.keySet()) {
            if ((key & 0xFFFF) == 2) {
                alternateActive = true;
                break;
            }
        }
        for (Map.Entry<Integer, AudioTrack> entry : tracks.entrySet()) {
            boolean main = (entry.getKey() & 0xFFFF) == 1;
            setVolume(entry.getValue(), outputGain * (alternateActive && main ? navigationMediaGain : 1f));
        }
    }

    public void stopStream(int decodeType, int audioType) {
        if (closed) {
            return;
        }
        int key = (decodeType << 16) | (audioType & 0xFFFF);
        try {
            audioExecutor.execute(() -> releaseTrack(key));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void play(AudioFrame frame) {
        if (closed) {
            return;
        }
        int key = (frame.getDecodeType() << 16) | (frame.getAudioType() & 0xFFFF);
        try {
            AudioTrack track = tracks.get(key);
            if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
                releaseTrack(key);
                int channelMask = frame.getChannelCount() == 1
                        ? AudioFormat.CHANNEL_OUT_MONO
                        : AudioFormat.CHANNEL_OUT_STEREO;
                int minimumBuffer = AudioTrack.getMinBufferSize(
                        frame.getSampleRate(), channelMask, AudioFormat.ENCODING_PCM_16BIT);
                if (minimumBuffer <= 0) {
                    throw new IOException("Unsupported PCM audio format: " + frame.getSampleRate()
                            + "Hz/" + frame.getChannelCount() + "ch");
                }
                int bufferSize = Math.max(minimumBuffer * 2, frame.getPcmData().length * 2);
                track = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        frame.getSampleRate(),
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                        AudioTrack.MODE_STREAM);
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    track.release();
                    throw new IOException("Android AudioTrack initialization failed");
                }
                setVolume(track, outputGain);
                track.play();
                tracks.put(key, track);
                updateTrackVolumes();
            }

            byte[] data = frame.getPcmData();
            int offset = 0;
            while (offset < data.length && !closed) {
                int written = track.write(data, offset, data.length - offset);
                if (written <= 0) {
                    throw new IOException("AudioTrack write failed: " + written);
                }
                offset += written;
            }
        } catch (Exception exception) {
            if (listener != null) {
                listener.onAudioError(exception.getMessage());
            }
            releaseTrack(key);
        }
    }

    private void releaseTrack(int key) {
        AudioTrack track = tracks.remove(key);
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
            }

            track.release();
            updateTrackVolumes();
        }
    }

    private static void setVolume(AudioTrack track, float gain) {
        if (android.os.Build.VERSION.SDK_INT >= 21) track.setVolume(gain);
        else track.setStereoVolume(gain, gain);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        audioExecutor.execute(() -> {
            for (Integer key : tracks.keySet().toArray(new Integer[0])) {
                releaseTrack(key);
            }
        });
        audioExecutor.shutdown();
    }
}
