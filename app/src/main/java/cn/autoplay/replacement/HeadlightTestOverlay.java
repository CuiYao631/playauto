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
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮按钮：模拟大灯开 / 关，用来在没有真实车机的环境里测试「夜间模式 - 大灯联动」。
 *
 * <p>真实链路依赖 {@code com.chinatsp.mcumanager.McuManager}（常安车机专用），普通手机
 * 上没有这个服务，{@link HeadlightNightMode#start()} 会直接失败，因此大灯联动无法测试。
 * 本窗口用 {@link HeadlightNightMode#simulate(int)} 伪造大灯状态，后续
 * 「回调 → 下发盒子 cmd 8 / 16|17 → CarPlay 昼夜切换」与真实大灯完全一致。
 *
 * <p>窗口可拖动，标题栏按住即可移动；点「×」关闭并把开关同步为关闭。
 */
final class HeadlightTestOverlay implements AutoCloseable {
    interface WindowListener {
        /** 用户点了「×」。宿主应把开关同步为关闭。 */
        void onCloseRequested();

        /**
         * 用户点了大灯按钮。
         *
         * @param setting {@code 1} 大灯开（夜间）、{@code 0} 大灯关（白天）
         */
        void onSimulate(int setting);
    }

    private static final String TAG = "AutoPlayHeadlightTest";

    static final String PREF_ENABLED = "headlight_test_overlay_enabled";
    private static final String PREF_X = "headlight_test_overlay_x";
    private static final String PREF_Y = "headlight_test_overlay_y";

    private final Context context;
    private final WindowListener listener;
    private final WindowManager windowManager;
    private final SharedPreferences prefs;

    private FrameLayout root;
    private TextView statusText;
    private TextView buttonOn;
    private TextView buttonOff;
    private WindowManager.LayoutParams params;
    private boolean showing;
    private boolean closed;
    private int screenWidth;
    private int screenHeight;
    /** 最近一次设定的大灯状态（{@code -1} 未知），只用于按钮高亮。 */
    private int currentSetting = -1;

    HeadlightTestOverlay(Context context, WindowListener listener) {
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
            Log.w(TAG, "大灯模拟窗口创建失败：取不到屏幕尺寸");
            return;
        }
        if (!AndroidCompat.canDrawOverlays(context)) {
            Log.w(TAG, "大灯模拟窗口需要「显示在其他应用上层」权限");
            return;
        }

        buildViews();
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt(PREF_X, Ui.dp(context, 12));
        params.y = prefs.getInt(PREF_Y, Math.round(screenHeight * 0.28f));
        clampPosition();

        try {
            windowManager.addView(root, params);
            showing = true;
            Log.i(TAG, "大灯模拟窗口已显示");
        } catch (RuntimeException exception) {
            Log.e(TAG, "大灯模拟窗口添加失败", exception);
            showing = false;
            root = null;
            statusText = null;
        }
    }

    void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        FrameLayout currentRoot = root;
        root = null;
        statusText = null;
        if (currentRoot != null) {
            try {
                windowManager.removeView(currentRoot);
            } catch (RuntimeException ignored) {
            }
        }
        Log.i(TAG, "大灯模拟窗口已隐藏");
    }

    /**
     * 更新状态摘要。可在任意线程调用；窗口未显示时忽略。
     *
     * @param setting   当前（模拟的）大灯状态，{@code -1} 未知
     * @param summary   已格式化的多行说明
     */
    void setStatus(int setting, String summary) {
        currentSetting = setting;
        if (!showing) {
            return;
        }
        TextView target = statusText;
        if (target == null) {
            return;
        }
        target.setText(summary);
        paintButtons();
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
        frame.addView(column);

        LinearLayout titleBar = new LinearLayout(context);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(Ui.dp(context, 8), Ui.dp(context, 4),
                Ui.dp(context, 4), Ui.dp(context, 4));

        titleBar.addView(Ui.text(context, "大灯模拟（拖动可移动）", 12, 0xFFFFFFFF, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

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

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 6), 0);

        TextView on = button("大灯开", 1);
        TextView off = button("大灯关", 0);
        buttonOn = on;
        buttonOff = off;
        buttons.addView(on);
        buttons.addView(off);
        column.addView(buttons);

        statusText = Ui.text(context, "", 10, 0xFFB8C0CC, false);
        statusText.setPadding(Ui.dp(context, 8), Ui.dp(context, 2),
                Ui.dp(context, 8), Ui.dp(context, 6));
        column.addView(statusText);

        root = frame;
        paintButtons();
    }

    private TextView button(String label, int setting) {
        TextView view = Ui.text(context, label, 13, 0xFFFFFFFF, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Ui.dp(context, 14), Ui.dp(context, 8),
                Ui.dp(context, 14), Ui.dp(context, 8));
        view.setTag(setting);
        view.setOnClickListener(clicked -> {
            if (listener != null) {
                listener.onSimulate(setting);
            }
            currentSetting = setting;
            paintButtons();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(Ui.dp(context, 3), Ui.dp(context, 3),
                Ui.dp(context, 3), Ui.dp(context, 3));
        view.setLayoutParams(params);
        return view;
    }

    /** 高亮当前状态对应的按钮，避免看不出按下的是哪个。 */
    private void paintButtons() {
        paintButton(buttonOn, currentSetting == 1);
        paintButton(buttonOff, currentSetting == 0);
    }

    private void paintButton(TextView view, boolean active) {
        if (view == null) {
            return;
        }
        view.setBackground(Ui.rounded(active ? 0xFF34D399 : 0xFF2B3038, Ui.dp(context, 8)));
        view.setTextColor(active ? 0xFF0F1115 : 0xFFFFFFFF);
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
        int width = params.width > 0 ? params.width : Ui.dp(context, 200);
        int height = params.height > 0 ? params.height : Ui.dp(context, 90);
        int maxX = Math.max(0, screenWidth - width);
        int maxY = Math.max(0, screenHeight - height);
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
            Log.w(TAG, "大灯模拟窗口更新失败", exception);
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
