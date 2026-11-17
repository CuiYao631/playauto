package cn.autoplay.replacement;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 用悬浮窗模拟「副屏」（原版称仪表投射）。
 *
 * <p>原版用 {@code android.app.Presentation} 绑定系统里真实存在的第二块 {@code Display}，
 * 因此必须有物理副屏且盒子在 {@code CMD_BOX_INFO} 的 {@code supportFeatures} 里报告
 * {@code naviScreen}。本类改用 {@code TYPE_APPLICATION_OVERLAY} 悬浮窗承载
 * {@link H264SurfaceRenderer}，把 {@code CMD_DASHBOARD_FRAME}(44) 这一路独立的 H.264
 * 流渲染到窗口里，从而在没有副屏的车机上也能验证副屏功能。
 *
 * <p>交互：拖动窗口移动、「◢」拖动改变宽度（高度按帧宽高比自动推导）、「×」关闭。
 * 窗口位置与尺寸会持久化，重启后恢复。
 *
 * <p>注意：副屏窗口只负责显示，不转发触摸事件——触摸坐标属于副屏坐标系，
 * 用主屏的 {@code CMD_MULTI_TOUCH}(23) 发送会被盒子当成主屏点击。
 */
final class SecondaryScreenOverlay implements H264SurfaceRenderer.Listener, AutoCloseable {
    interface WindowListener {
        /** 用户点了「×」。宿主应把开关同步为关闭。 */
        void onCloseRequested();

        /** 窗口尺寸变化（像素），宿主应把新的 {@code naviScreenInfo} 同步给盒子。 */
        void onLayoutChanged(int widthPx, int heightPx);
    }

    private static final String TAG = "AutoPlaySecondary";

    static final String PREF_ENABLED = "secondary_screen_enabled";
    static final String PREF_SCALE = "secondary_screen_scale";
    static final String PREF_WIDTH_PX = "secondary_screen_width_px";
    static final String PREF_X = "secondary_screen_x";
    static final String PREF_Y = "secondary_screen_y";

    /** 预设宽度占屏比，对应设置页「小 / 中 / 大」。 */
    private static final float[] SCALES = {0.30f, 0.45f, 0.62f};
    private static final float DEFAULT_ASPECT = 9f / 16f;
    private static final float MIN_WIDTH_RATIO = 0.18f;
    private static final float MAX_WIDTH_RATIO = 0.95f;

    private final Context context;
    private final WindowListener listener;
    private final WindowManager windowManager;
    private final SharedPreferences prefs;

    private FrameLayout root;
    private H264SurfaceRenderer renderer;
    private TextView hint;
    private WindowManager.LayoutParams params;
    private boolean showing;
    private boolean closed;

    /** 高 / 宽，由第一帧决定，未知时按 16:9 估算。 */
    private float aspect = DEFAULT_ASPECT;
    private int frameWidth;
    private int frameHeight;
    private int screenWidth;
    private int screenHeight;

    SecondaryScreenOverlay(Context context, WindowListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = this.context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    }

    boolean isShowing() {
        return showing;
    }

    /** 按设置页的预设档位计算宽度并立即应用（不改变显示状态）。 */
    void applyScale(int index) {
        int clamped = Math.max(0, Math.min(index, SCALES.length - 1));
        prefs.edit().putInt(PREF_SCALE, clamped).remove(PREF_WIDTH_PX).apply();
        if (params != null) {
            params.width = clampWidth(Math.round(screenWidth() * SCALES[clamped]));
            params.height = Math.round(params.width * aspect);
            clampPosition();
            updateWindow();
            notifyLayout();
        }
    }

    void show() {
        if (closed || showing) {
            return;
        }
        measureScreen();
        if (screenWidth <= 0 || screenHeight <= 0) {
            Log.w(TAG, "副屏悬浮窗创建失败：取不到屏幕尺寸");
            return;
        }
        if (!AndroidCompat.canDrawOverlays(context)) {
            Log.w(TAG, "副屏悬浮窗需要「显示在其他应用上层」权限");
            return;
        }

        buildViews();

        int width = clampWidth(resolveWidth());
        params = new WindowManager.LayoutParams(
                width,
                Math.round(width * aspect),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt(PREF_X, defaultX(width));
        params.y = prefs.getInt(PREF_Y, defaultY(Math.round(width * aspect)));
        clampPosition();

        try {
            windowManager.addView(root, params);
            showing = true;
            Log.i(TAG, "副屏悬浮窗已显示 " + params.width + "x" + params.height
                    + " @" + params.x + "," + params.y);
            notifyLayout();
        } catch (RuntimeException exception) {
            Log.e(TAG, "副屏悬浮窗添加失败", exception);
            showing = false;
            root = null;
            renderer = null;
            hint = null;
        }
    }

    void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        H264SurfaceRenderer current = renderer;
        renderer = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException ignored) {
            }
        }
        FrameLayout currentRoot = root;
        root = null;
        hint = null;
        if (currentRoot != null) {
            try {
                windowManager.removeView(currentRoot);
            } catch (RuntimeException ignored) {
            }
        }
        Log.i(TAG, "副屏悬浮窗已隐藏");
    }

    /** 把盒子下发的副屏帧交给解码器。可在协议线程调用。 */
    void submitFrame(VideoFrame frame) {
        H264SurfaceRenderer target = renderer;
        if (!showing || closed || target == null || frame == null) {
            return;
        }
        if (frameWidth != frame.getWidth() || frameHeight != frame.getHeight()) {
            frameWidth = frame.getWidth();
            frameHeight = frame.getHeight();
            float next = (float) frameHeight / frameWidth;
            if (Math.abs(next - aspect) > 0.02f) {
                aspect = next;
                postToMain(this::applyAspect);
            }
        }
        postToMain(() -> hideHint());
        target.submitFrame(frame);
    }

    /** 主动上报当前尺寸，用于连接建立后同步 {@code naviScreenInfo}。 */
    void notifyLayout() {
        if (listener != null && params != null) {
            listener.onLayoutChanged(params.width, params.height);
        }
    }

    @Override
    public void close() {
        closed = true;
        hide();
    }

    // ---- H264SurfaceRenderer.Listener ----

    @Override
    public void onDecoderError(String error) {
        Log.w(TAG, "副屏解码失败：" + error);
    }

    @Override
    public void onKeyFrameNeeded() {
        // 副屏是纯显示，关键帧由宿主在需要时统一请求（见 ConnectionService）。
    }

    // ---- 视图 ----

    private void buildViews() {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(Ui.rounded(0xFF000000, 0));

        H264SurfaceRenderer surface = new H264SurfaceRenderer(context, this);
        surface.setDecoderMode(prefs.getInt("decoder_mode", VideoOptions.HARDWARE));
        surface.setFrameRate(VideoPreferences.frameRate(prefs));
        frame.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View dragLayer = new View(context);
        dragLayer.setClickable(true);
        dragLayer.setOnTouchListener(new DragTouchListener(false));
        frame.addView(dragLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView waiting = Ui.text(context, "副屏：等待盒子画面", 12, 0xCCFFFFFF, false);
        waiting.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        frame.addView(waiting, hintParams);

        TextView closeButton = Ui.text(context, "×", 18, 0xFFFFFFFF, true);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setBackground(Ui.rounded(0x99000000, Ui.dp(context, 999)));
        closeButton.setOnClickListener(view -> {
            if (listener != null) {
                listener.onCloseRequested();
            } else {
                hide();
            }
        });
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                Ui.dp(context, 32), Ui.dp(context, 32), Gravity.TOP | Gravity.END);
        closeParams.topMargin = Ui.dp(context, 6);
        closeParams.rightMargin = Ui.dp(context, 6);
        frame.addView(closeButton, closeParams);

        TextView resizeHandle = Ui.text(context, "◢", 14, 0xCCFFFFFF, false);
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setBackground(Ui.rounded(0x66000000, Ui.dp(context, 6)));
        resizeHandle.setOnTouchListener(new DragTouchListener(true));
        FrameLayout.LayoutParams resizeParams = new FrameLayout.LayoutParams(
                Ui.dp(context, 28), Ui.dp(context, 28), Gravity.BOTTOM | Gravity.END);
        resizeParams.bottomMargin = Ui.dp(context, 4);
        resizeParams.rightMargin = Ui.dp(context, 4);
        frame.addView(resizeHandle, resizeParams);

        root = frame;
        renderer = surface;
        hint = waiting;
    }

    private void hideHint() {
        if (hint != null && hint.getVisibility() != View.GONE) {
            hint.setVisibility(View.GONE);
        }
    }

    private void applyAspect() {
        if (params == null) {
            return;
        }
        params.height = Math.round(params.width * aspect);
        clampPosition();
        updateWindow();
        notifyLayout();
    }

    private void updateWindow() {
        if (!showing || root == null || params == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(root, params);
        } catch (RuntimeException exception) {
            Log.w(TAG, "副屏悬浮窗更新失败", exception);
        }
    }

    // ---- 拖动 / 缩放 ----

    private final class DragTouchListener implements View.OnTouchListener {
        private final boolean resize;
        private float downRawX;
        private float downRawY;
        private int downX;
        private int downY;
        private int downWidth;
        private boolean dragging;

        DragTouchListener(boolean resize) {
            this.resize = resize;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (params == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = params.x;
                    downY = params.y;
                    downWidth = params.width;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = Math.round(event.getRawX() - downRawX);
                    int dy = Math.round(event.getRawY() - downRawY);
                    if (!dragging && Math.abs(dx) + Math.abs(dy) < Ui.dp(context, 4)) {
                        return true;
                    }
                    dragging = true;
                    if (resize) {
                        params.width = clampWidth(downWidth + dx);
                        params.height = Math.round(params.width * aspect);
                        clampPosition();
                    } else {
                        params.x = downX + dx;
                        params.y = downY + dy;
                        clampPosition();
                    }
                    updateWindow();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        persistLayout();
                        notifyLayout();
                    }
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---- 尺寸与位置 ----

    private int resolveWidth() {
        int saved = prefs.getInt(PREF_WIDTH_PX, 0);
        if (saved > 0) {
            return saved;
        }
        int index = Math.max(0, Math.min(prefs.getInt(PREF_SCALE, 1), SCALES.length - 1));
        return Math.round(screenWidth * SCALES[index]);
    }

    private int clampWidth(int width) {
        int min = Math.round(screenWidth * MIN_WIDTH_RATIO);
        int max = Math.round(screenWidth * MAX_WIDTH_RATIO);
        return Math.max(min, Math.min(width, max));
    }

    private void clampPosition() {
        if (params == null) {
            return;
        }
        int maxX = Math.max(0, screenWidth - params.width);
        int maxY = Math.max(0, screenHeight - params.height);
        params.x = Math.max(0, Math.min(params.x, maxX));
        params.y = Math.max(0, Math.min(params.y, maxY));
    }

    private void persistLayout() {
        if (params == null) {
            return;
        }
        prefs.edit()
                .putInt(PREF_X, params.x)
                .putInt(PREF_Y, params.y)
                .putInt(PREF_WIDTH_PX, params.width)
                .apply();
    }

    private int defaultX(int width) {
        return Math.max(0, screenWidth - width - Ui.dp(context, 16));
    }

    private int defaultY(int height) {
        return Math.max(0, Math.min(Ui.dp(context, 72), screenHeight - height));
    }

    private int screenWidth() {
        measureScreen();
        return screenWidth;
    }

    private void measureScreen() {
        if (screenWidth > 0 && screenHeight > 0) {
            return;
        }
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            android.view.Display display = windowManager.getDefaultDisplay();
            if (Build.VERSION.SDK_INT >= 17) {
                display.getRealMetrics(metrics);
            } else {
                display.getMetrics(metrics);
            }
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
        } catch (RuntimeException exception) {
            Log.w(TAG, "读取屏幕尺寸失败", exception);
        }
    }

    private void postToMain(Runnable action) {
        if (root != null) {
            root.post(action);
        }
    }
}
