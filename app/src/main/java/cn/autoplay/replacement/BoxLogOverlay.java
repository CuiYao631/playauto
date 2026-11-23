package cn.autoplay.replacement;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 悬浮日志窗：实时显示「盒子上报的指令」。
 *
 * <p>调试用窗口，与副屏悬浮窗一样用 {@code TYPE_APPLICATION_OVERLAY} 承载，
 * 因此切换设置页、看投屏画面时都能同时观察协议走向。内容由
 * {@link ConnectionService} 的环形缓冲提供，最新一条在最上面。
 *
 * <p>交互：拖动标题栏移动、「清空」丢弃已积累的日志、「×」关闭窗口。
 */
final class BoxLogOverlay implements AutoCloseable {
    interface WindowListener {
        /** 用户点了「×」。宿主应把开关同步为关闭。 */
        void onCloseRequested();

        /** 用户点了「清空」。宿主应清空日志缓冲。 */
        void onClearRequested();
    }

    private static final String TAG = "AutoPlayBoxLog";

    static final String PREF_ENABLED = "box_log_overlay_enabled";
    private static final String PREF_X = "box_log_overlay_x";
    private static final String PREF_Y = "box_log_overlay_y";
    /** 窗口宽度占屏比。 */
    private static final float WIDTH_RATIO = 0.86f;
    /** 窗口高度占屏比。 */
    private static final float HEIGHT_RATIO = 0.34f;

    private final Context context;
    private final WindowListener listener;
    private final WindowManager windowManager;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private TextView text;
    private WindowManager.LayoutParams params;
    private boolean showing;
    private boolean closed;
    private int screenWidth;
    private int screenHeight;
    private String pendingText = "";

    BoxLogOverlay(Context context, WindowListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = this.context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    }

    boolean isShowing() {
        return showing;
    }

    void show() {
        if (closed || showing) {
            return;
        }
        measureScreen();
        if (screenWidth <= 0 || screenHeight <= 0) {
            Log.w(TAG, "日志窗口创建失败：取不到屏幕尺寸");
            return;
        }
        if (!AndroidCompat.canDrawOverlays(context)) {
            Log.w(TAG, "日志窗口需要「显示在其他应用上层」权限");
            return;
        }

        buildViews();

        int width = Math.round(screenWidth * WIDTH_RATIO);
        int height = Math.round(screenHeight * HEIGHT_RATIO);
        params = new WindowManager.LayoutParams(
                width,
                height,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt(PREF_X, Math.max(0, (screenWidth - width) / 2));
        // 默认贴屏幕底部，尽量少挡投屏画面；位置可拖动并记忆
        params.y = prefs.getInt(PREF_Y,
                Math.max(0, screenHeight - height - Ui.dp(context, 16)));
        clampPosition();

        try {
            windowManager.addView(root, params);
            showing = true;
            Log.i(TAG, "日志窗口已显示 " + width + "x" + height);
            applyText(pendingText);
        } catch (RuntimeException exception) {
            Log.e(TAG, "日志窗口添加失败", exception);
            showing = false;
            root = null;
            text = null;
        }
    }

    void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        FrameLayout currentRoot = root;
        root = null;
        text = null;
        if (currentRoot != null) {
            try {
                windowManager.removeView(currentRoot);
            } catch (RuntimeException ignored) {
            }
        }
        Log.i(TAG, "日志窗口已隐藏");
    }

    /**
     * 更新窗口内容。可在任意线程调用；窗口未显示时只缓存，等下次 show() 使用。
     *
     * @param content 已格式化好的多行文本，最新一条在最前
     */
    void setText(String content) {
        String value = content == null ? "" : content;
        pendingText = value;
        if (!showing) {
            return;
        }
        handler.post(() -> applyText(value));
    }

    private void applyText(String value) {
        TextView target = text;
        if (target == null || value == null) {
            return;
        }
        if (!value.contentEquals(target.getText())) {
            target.setText(value);
        }
    }

    @Override
    public void close() {
        closed = true;
        hide();
    }

    // ---- 视图 ----

    private void buildViews() {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(Ui.rounded(0xE6000000, Ui.dp(context, 10)));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        frame.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout titleBar = new LinearLayout(context);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(Ui.dp(context, 8), Ui.dp(context, 4),
                Ui.dp(context, 4), Ui.dp(context, 4));

        TextView title = Ui.text(context, "盒子指令日志", 12, 0xFFFFFFFF, true);
        titleBar.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView clearButton = Ui.text(context, "清空", 12, 0xFF7FC4FF, false);
        clearButton.setPadding(Ui.dp(context, 8), Ui.dp(context, 2),
                Ui.dp(context, 8), Ui.dp(context, 2));
        clearButton.setOnClickListener(view -> {
            pendingText = "";
            applyText("");
            if (listener != null) listener.onClearRequested();
        });
        titleBar.addView(clearButton);

        TextView closeButton = Ui.text(context, "×", 16, 0xFFFFFFFF, true);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0);
        closeButton.setOnClickListener(view -> {
            if (listener != null) {
                listener.onCloseRequested();
            } else {
                hide();
            }
        });
        titleBar.addView(closeButton);
        titleBar.setOnTouchListener(new DragTouchListener());
        column.addView(titleBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 32)));

        text = Ui.text(context, "", 10, 0xFFE6E6E6, false);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        text.setPadding(Ui.dp(context, 8), Ui.dp(context, 2),
                Ui.dp(context, 8), Ui.dp(context, 6));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        column.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root = frame;
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int downX;
        private int downY;
        private boolean dragging;

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
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = Math.round(event.getRawX() - downRawX);
                    int dy = Math.round(event.getRawY() - downRawY);
                    if (!dragging && Math.abs(dx) + Math.abs(dy) < Ui.dp(context, 4)) {
                        return true;
                    }
                    dragging = true;
                    params.x = downX + dx;
                    params.y = downY + dy;
                    clampPosition();
                    updateWindow();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        persistLayout();
                    }
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---- 尺寸与位置 ----

    private void clampPosition() {
        if (params == null) {
            return;
        }
        int maxX = Math.max(0, screenWidth - params.width);
        int maxY = Math.max(0, screenHeight - params.height);
        params.x = Math.max(0, Math.min(params.x, maxX));
        params.y = Math.max(0, Math.min(params.y, maxY));
    }

    private void updateWindow() {
        if (!showing || root == null || params == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(root, params);
        } catch (RuntimeException exception) {
            Log.w(TAG, "日志窗口更新失败", exception);
        }
    }

    private void persistLayout() {
        if (params == null) {
            return;
        }
        prefs.edit()
                .putInt(PREF_X, params.x)
                .putInt(PREF_Y, params.y)
                .apply();
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
}
