package cn.autoplay.replacement;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaCodecInfo;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.MotionEvent;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class H264SurfaceRenderer extends FrameLayout
        implements SurfaceHolder.Callback, TextureView.SurfaceTextureListener, AutoCloseable {
    public interface Listener {
        void onDecoderError(String error);

        default void onTouchData(byte[] touchData) {
        }

        default void onKeyFrameNeeded() {
        }

    }

    private final VideoFrameQueue frameQueue = new VideoFrameQueue(VideoOptions.DEFAULT_FPS * 2);
    private long decodingGeneration = -1;
    private final ThreadPoolExecutor decoderExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread thread = new Thread(runnable, "autoplay-h264-renderer");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
    private final Listener listener;

    private volatile Surface outputSurface;
    private volatile boolean closed;
    private volatile long lastKeyFrameRequestMs;
    private MediaCodec decoder;
    private cn.manstep.phonemirrorBox.OpenH264Decoder openH264;
    private byte[] decodedI420;
    private volatile OpenH264GlView softwareView;
    private int decoderWidth;
    private int decoderHeight;
    private byte[] sps;
    private byte[] pps;
    private byte[] pendingCodecConfig;
    private volatile int decoderMode = VideoOptions.HARDWARE;
    private volatile int selectedFrameRate = VideoOptions.DEFAULT_FPS;
    private int activeFrameRate;
    private int activeDecoderMode = -1;
    private boolean textureRendering;
    private Surface textureSurface;
    private Surface decoderSurface;
    private final ConcurrentLinkedQueue<Surface> retiredSurfaces = new ConcurrentLinkedQueue<>();

    public H264SurfaceRenderer(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        createOutputView();
    }

    public H264SurfaceRenderer(Context context, AttributeSet attributes) {
        super(context, attributes);
        this.listener = null;
        createOutputView();
    }

    void setTextureRendering(boolean enabled) {
        if (textureRendering != enabled) {
            textureRendering = enabled;
            if (decoderMode == 3) return;
            outputSurface = null;
            removeAllViews();
            createOutputView();
        }
    }

    private void createOutputView() {
        android.view.View view;
        if (decoderMode == 3) {
            OpenH264GlView software = new OpenH264GlView(getContext(), listener);
            softwareView = software;
            software.getHolder().addCallback(this);
            view = software;
        } else if (textureRendering) {
            TextureView texture = new TextureView(getContext());
            texture.setSurfaceTextureListener(this);
            view = texture;
        } else {
            SurfaceView surface = new SurfaceView(getContext());
            surface.getHolder().addCallback(this);
            view = surface;
        }
        view.setOnTouchListener((target, event) -> onTouchEvent(event));
        addView(view, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void submitFrame(VideoFrame frame) {
        if (closed || frame == null) {
            return;
        }
        if (!frameQueue.offer(frame)) {
            android.util.Log.w("AutoPlayVideo", "Video queue overflow; discard dependent frames and await IDR");
            requestKeyFrame();
        }
        scheduleDecode();
    }

    void setDecoderMode(int mode) {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("Invalid decoder mode: " + mode);
        }
        boolean changeOutput = (decoderMode == 3) != (mode == 3);
        decoderMode = mode;
        if (changeOutput) {
            outputSurface = null;
            softwareView = null;
            frameQueue.clear();
            removeAllViews();
            createOutputView();
            scheduleDecode();
        }
        requestKeyFrame();
    }

    void setFrameRate(int rate) {
        if (rate <= 0 || rate > 60) throw new IllegalArgumentException("Invalid decoder frame rate: " + rate);
        selectedFrameRate = rate;
        if (!frameQueue.setCapacity(rate * 2)) {
            android.util.Log.w("AutoPlayVideo", "Video rate changed; queued data exceeds two-second limit");
        }
        requestKeyFrame();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
                && action != MotionEvent.ACTION_POINTER_DOWN && action != MotionEvent.ACTION_POINTER_UP) {
            return true;
        }

        int pointerCount = event.getPointerCount();
        if (pointerCount <= 0 || pointerCount > TouchFrame.MAX_POINTERS
                || getWidth() <= 0 || getHeight() <= 0) {
            return true;
        }
        int firstPointer = 0;
        int lastPointer = pointerCount;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            firstPointer = event.getActionIndex();
            lastPointer = firstPointer + 1;
        }

        int count = lastPointer - firstPointer;
        float[] normalizedX = new float[count];
        float[] normalizedY = new float[count];
        int[] pointerIds = new int[count];
        for (int index = 0; index < count; index++) {
            int pointerIndex = firstPointer + index;
            normalizedX[index] = event.getX(pointerIndex) / getWidth();
            normalizedY[index] = event.getY(pointerIndex) / getHeight();
            pointerIds[index] = event.getPointerId(pointerIndex);
            if (pointerIds[index] < 0 || pointerIds[index] >= TouchFrame.MAX_POINTERS) {
                return true;
            }
        }

        int actionState = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                ? 1 : action == MotionEvent.ACTION_MOVE ? 2 : 0;
        if (listener != null) {
            listener.onTouchData(TouchFrame.encode(normalizedX, normalizedY, pointerIds, actionState));
        }
        return true;
    }

    private void scheduleDecode() {
        if (closed) {
            return;
        }
        try {
            decoderExecutor.execute(() -> {
                VideoFrameQueue.Entry pending = null;
                long inputWaitStarted = 0;
                if (!retiredSurfaces.isEmpty() || outputSurface == null) {
                    releaseDecoder();
                    releaseRetiredSurfaces();
                }
                if (outputSurface == null || !outputSurface.isValid()) {
                    return;
                }
                while (!closed) {
                    try {
                        if (outputSurface == null || !outputSurface.isValid()) {
                            releaseDecoder();
                            releaseRetiredSurfaces();
                            return;
                        }
                        if (decodingGeneration != frameQueue.generation()) {
                            releaseDecoder();
                            pending = null;
                            decodingGeneration = frameQueue.generation();
                            requestKeyFrame();
                        }
                        if (pending == null) {
                            pending = frameQueue.poll();
                            inputWaitStarted = android.os.SystemClock.uptimeMillis();
                        }
                        if (pending == null) {
                            if (decoder != null) drainOutput(10000L);
                            else Thread.sleep(10);
                            continue;
                        }
                        if (pending.generation != decodingGeneration) {
                            pending = null;
                            continue;
                        }
                        Surface surface = outputSurface;
                        if (surface == null || !surface.isValid()) {
                            releaseDecoder();
                            frameQueue.clear();
                            requestKeyFrame();
                            return;
                        }
                        if (decode(pending.frame)) {
                            pending = null;
                        } else if (android.os.SystemClock.uptimeMillis() - inputWaitStarted >= 1000) {
                            throw new IOException("H.264 decoder input stalled for 1 second");
                        }
                    } catch (Exception | LinkageError exception) {
                        releaseDecoder();
                        frameQueue.clear();
                        requestKeyFrame();
                        android.util.Log.e("AutoPlayVideo", "Video decode failed; awaiting new IDR", exception);
                        if (listener != null) {
                            post(() -> listener.onDecoderError(exception.getMessage()));
                        }
                        return;
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private boolean decode(VideoFrame frame) throws IOException {
        byte[] data = frame.getH264Data();
        captureParameterSets(data);
        Surface surface = outputSurface;
        if (surface == null || !surface.isValid()) {
            return false;
        }
        int requestedMode = decoderMode;
        int requestedFrameRate = selectedFrameRate;
        if ((requestedMode == 3 ? openH264 == null : decoder == null)
                || frame.getWidth() != decoderWidth || frame.getHeight() != decoderHeight
                || requestedMode != activeDecoderMode || decoderSurface != surface
                || activeFrameRate != requestedFrameRate) {
            releaseDecoder();
            if (sps == null || pps == null || !containsIdr(data)) {
                requestKeyFrame();
                return true;
            }
            if (requestedMode == 3) {
                openH264 = new cn.manstep.phonemirrorBox.OpenH264Decoder();
                decodedI420 = new byte[Math.max(4392000, frame.getWidth() * frame.getHeight() * 3 / 2)];
                byte[] config = new byte[sps.length + pps.length];
                System.arraycopy(sps, 0, config, 0, sps.length);
                System.arraycopy(pps, 0, config, sps.length, pps.length);
                openH264.decode(config, decodedI420);
                android.util.Log.i("AutoPlayVideo", "Configure original OpenH264 + I420 GLES2"
                        + " size=" + frame.getWidth() + "x" + frame.getHeight());
            } else {
                MediaFormat format = MediaFormat.createVideoFormat(
                        AndroidCompat.AVC_MIME_TYPE, frame.getWidth(), frame.getHeight());
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
                        Math.max(data.length + sps.length + pps.length, 1024 * 1024));
                format.setInteger(MediaFormat.KEY_FRAME_RATE, requestedFrameRate);
                if (Build.VERSION.SDK_INT >= 23) {
                    format.setInteger("operating-rate", (int) (requestedFrameRate * 1.3f));
                }
                decoder = createDecoder(format, requestedMode);
                android.util.Log.i("AutoPlayVideo", "Configure decoder=" + decoder.getName()
                        + " size=" + frame.getWidth() + "x" + frame.getHeight()
                        + " mode=" + requestedMode + " texture=" + textureRendering);
                decoder.configure(format, surface, null, 0);
                decoder.start();
                pendingCodecConfig = new byte[sps.length + pps.length];
                System.arraycopy(sps, 0, pendingCodecConfig, 0, sps.length);
                System.arraycopy(pps, 0, pendingCodecConfig, sps.length, pps.length);
            }
            decoderWidth = frame.getWidth();
            decoderHeight = frame.getHeight();
            activeDecoderMode = requestedMode;
            activeFrameRate = requestedFrameRate;
            decoderSurface = surface;
        }

        if (requestedMode == 3) {
            int size = openH264.decode(data, decodedI420);
            OpenH264GlView target = softwareView;
            if (size > 0 && target != null) {
                target.submit(decodedI420, size, openH264.width(), openH264.height());
            }
            return true;
        }

        if (pendingCodecConfig != null) {
            byte[] combined = new byte[pendingCodecConfig.length + data.length];
            System.arraycopy(pendingCodecConfig, 0, combined, 0, pendingCodecConfig.length);
            System.arraycopy(data, 0, combined, pendingCodecConfig.length, data.length);
            data = combined;
        }
        drainOutput();
        int inputIndex = decoder.dequeueInputBuffer(10000L);
        if (inputIndex < 0) {
            drainOutput();
            return false;
        }
        if (inputIndex >= 0) {
            ByteBuffer input = Build.VERSION.SDK_INT >= 21
                    ? decoder.getInputBuffer(inputIndex) : decoder.getInputBuffers()[inputIndex];
            if (input == null || data.length > input.capacity()) {
                throw new IOException("H.264 frame exceeds decoder input buffer");
            }
            input.clear();
            input.put(data);
            long presentationTimeUs = frame.getPresentationTimeUs() > 0
                    ? frame.getPresentationTimeUs()
                    : System.nanoTime() / 1000L;
            decoder.queueInputBuffer(inputIndex, 0, data.length, presentationTimeUs, 0);
            pendingCodecConfig = null;
        }

        drainOutput();
        return true;
    }

    private void drainOutput() {
        drainOutput(0L);
    }

    private void drainOutput(long waitUs) {
        while (true) {
            int index = decoder.dequeueOutputBuffer(outputInfo, waitUs);
            if (index >= 0) {
                decoder.releaseOutputBuffer(index, true);
                waitUs = 0L;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                android.util.Log.i("AutoPlayVideo", "Output format: " + decoder.getOutputFormat());
            } else if (index != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                return;
            }
        }
    }

    private MediaCodec createDecoder(MediaFormat format, int mode) throws IOException {
        if (mode == 0 || mode == VideoOptions.HARDWARE) {
            return MediaCodec.createDecoderByType(AndroidCompat.AVC_MIME_TYPE);
        }
        for (MediaCodecInfo info : AndroidCompat.decoders()) {
            if (info.isEncoder()) {
                continue;
            }
            boolean software = Build.VERSION.SDK_INT >= 29 ? info.isSoftwareOnly()
                    : info.getName().startsWith("OMX.google.") || info.getName().startsWith("c2.android.");
            if (software != (mode == 2)) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (AndroidCompat.AVC_MIME_TYPE.equalsIgnoreCase(type)
                        && (Build.VERSION.SDK_INT < 21
                                || info.getCapabilitiesForType(type).isFormatSupported(format))) {
                    return MediaCodec.createByCodecName(info.getName());
                }
            }
        }
        throw new IOException(mode == 2 ? "设备没有支持当前视频格式的软件 H.264 解码器"
                : "设备没有支持当前视频格式的硬件 H.264 解码器");
    }

    private void captureParameterSets(byte[] data) {
        int start = findStartCode(data, 0);
        while (start >= 0) {
            int nalStart = start + startCodeLength(data, start);
            int nextStart = findStartCode(data, nalStart);
            int nalEnd = nextStart >= 0 ? nextStart : data.length;
            if (nalStart < nalEnd) {
                int nalType = data[nalStart] & 0x1F;
                if (nalType == 7 || nalType == 8) {
                    byte[] nal = Arrays.copyOfRange(data, start, nalEnd);
                    if (nalType == 7 && !Arrays.equals(sps, nal)) {
                        sps = nal;
                        releaseDecoder();
                    } else if (nalType == 8 && !Arrays.equals(pps, nal)) {
                        pps = nal;
                        releaseDecoder();
                    }
                }
            }
            start = nextStart;
        }
    }

    private static boolean containsIdr(byte[] data) {
        int start = findStartCode(data, 0);
        while (start >= 0) {
            int nalStart = start + startCodeLength(data, start);
            if (nalStart < data.length && (data[nalStart] & 0x1F) == 5) {
                return true;
            }
            start = findStartCode(data, nalStart);
        }
        return false;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int index = Math.max(0, from); index + 3 < data.length; index++) {
            if (data[index] == 0 && data[index + 1] == 0) {
                if (data[index + 2] == 1) {
                    return index;
                }
                if (index + 4 <= data.length && data[index + 2] == 0 && data[index + 3] == 1) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int index) {
        return data[index + 2] == 1 ? 3 : 4;
    }

    private void releaseDecoder() {
        pendingCodecConfig = null;
        if (openH264 != null) {
            openH264.close();
            openH264 = null;
            decodedI420 = null;
        }
        MediaCodec currentDecoder = decoder;
        decoder = null;
        decoderWidth = 0;
        decoderHeight = 0;
        decoderSurface = null;
        if (currentDecoder == null) {
            return;
        }
        try {
            currentDecoder.stop();
        } catch (IllegalStateException ignored) {
        }
        try {
            currentDecoder.release();
        } catch (IllegalStateException ignored) {
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        outputSurface = holder.getSurface();
        requestKeyFrame();
        scheduleDecode();
    }

    private void requestKeyFrame() {
        long now = android.os.SystemClock.uptimeMillis();
        if (listener == null || now - lastKeyFrameRequestMs < 1000) {
            return;
        }
        lastKeyFrameRequestMs = now;
        listener.onKeyFrameNeeded();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        outputSurface = holder.getSurface();
        scheduleDecode();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        outputSurface = null;
        if (!closed) {
            scheduleDecode();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        textureSurface = new Surface(texture);
        outputSurface = textureSurface;
        requestKeyFrame();
        scheduleDecode();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        scheduleDecode();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        outputSurface = null;
        Surface oldSurface = textureSurface;
        textureSurface = null;
        if (oldSurface != null) {
            if (closed) {
                oldSurface.release();
            } else {
                retiredSurfaces.add(oldSurface);
                scheduleDecode();
            }
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    private void releaseRetiredSurfaces() {
        Surface surface;
        while ((surface = retiredSurfaces.poll()) != null) {
            surface.release();
        }
    }

    @Override
    public void close() {
        closed = true;
        frameQueue.clear();
        try {
            decoderExecutor.execute(() -> {
                releaseDecoder();
                releaseRetiredSurfaces();
            });
            decoderExecutor.shutdown();
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            decoderExecutor.shutdownNow();
        }
    }
}
