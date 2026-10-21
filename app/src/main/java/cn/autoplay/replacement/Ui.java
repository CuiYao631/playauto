package cn.autoplay.replacement;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    private static final float CORNER_RADIUS_SCALE = 0.75f;

    static final int STATE_IDLE = 0;
    static final int STATE_OK = 1;
    static final int STATE_WAIT = 2;
    static final int STATE_ERROR = 3;

    // Palette is swapped by applyTheme(); views read it when they are built.
    static int BACKGROUND;
    static int CARD;
    static int TEXT;
    static int TEXT_SECONDARY;
    static int TEXT_DISABLED;
    static int ACCENT;
    static int ACCENT_SOFT;
    static int CONTROL;
    static int CONTROL_BORDER;
    static int ON_ACCENT;
    static int DIVIDER;
    static int WARNING;
    static int ERROR;
    static int IDLE;
    static int HIGHLIGHT;
    static int RIPPLE;
    static int BG_TOP;
    static int BG_BOTTOM;

    static {
        applyTheme(false);
    }

    private Ui() {
    }

    static void applyTheme(boolean dark) {
        if (dark) {
            BACKGROUND = 0xFF0F1115;
            CARD = 0xFF1A1D23;
            TEXT = 0xFFF3F4F6;
            TEXT_SECONDARY = 0xFF9CA3AF;
            TEXT_DISABLED = 0xFF6B7280;
            ACCENT = 0xFF34D399;
            ACCENT_SOFT = 0xFF20483B;
            CONTROL = 0xFF263C35;
            CONTROL_BORDER = 0xFF4C7866;
            ON_ACCENT = 0xFF0F1115;
            DIVIDER = 0xFF262A32;
            WARNING = 0xFFF59E0B;
            ERROR = 0xFFF87171;
            IDLE = 0xFF6B7280;
            HIGHLIGHT = 0xFF22D3EE;
            RIPPLE = 0x22FFFFFF;
            BG_TOP = 0xFF161A22;
            BG_BOTTOM = 0xFF0A0C10;
        } else {
            BACKGROUND = 0xFFF2F4F7;
            CARD = 0xFFFFFFFF;
            TEXT = 0xFF111827;
            TEXT_SECONDARY = 0xFF6B7280;
            TEXT_DISABLED = 0xFF9CA3AF;
            ACCENT = 0xFF0A7D5A;
            ACCENT_SOFT = 0xFFCCE8DC;
            CONTROL = 0xFFD8EAE3;
            CONTROL_BORDER = 0xFFA8C8BB;
            ON_ACCENT = 0xFFFFFFFF;
            DIVIDER = 0xFFE9ECF0;
            WARNING = 0xFFB45309;
            ERROR = 0xFFB42318;
            IDLE = 0xFF9CA3AF;
            HIGHLIGHT = 0xFF0891B2;
            RIPPLE = 0x14000000;
            BG_TOP = 0xFFF8FAFD;
            BG_BOTTOM = 0xFFE8EDF4;
        }
    }

    static GradientDrawable pageBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM});
    }

    static GradientDrawable glow(int color, int alpha, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        drawable.setGradientRadius(radiusPx);
        drawable.setColors(new int[]{(color & 0x00FFFFFF) | (alpha << 24), color & 0x00FFFFFF});
        return drawable;
    }

    static GradientDrawable oval(int color, int sizePx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setSize(sizePx, sizePx);
        return drawable;
    }

    /** A pill used for the floating controls. */
    static void floating(View view, float radiusDp) {
        Context context = view.getContext();
        GradientDrawable background = rounded(CONTROL, dp(context, radiusDp));
        background.setStroke(dp(context, 1), CONTROL_BORDER);
        view.setBackground(background);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(0);
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx * CORNER_RADIUS_SCALE);
        return drawable;
    }

    static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, dp(context, 16)));
        if (Build.VERSION.SDK_INT >= 21) {
            card.setClipToOutline(true);
            card.setElevation(dp(context, 1));
        }
        return card;
    }

    static TextView text(Context context, CharSequence value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp * fontScale(context));
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static float fontScale(Context context) {
        int size = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getInt("font_size", 0);
        return size == 1 ? 1.15f : size == 2 ? 1.3f : 1f;
    }

    static Button button(Context context, String label, boolean primary) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14 * fontScale(context));
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(primary ? ON_ACCENT : ACCENT);
        if (Build.VERSION.SDK_INT >= 21) button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        int fill = primary ? ACCENT : ACCENT_SOFT;
        int ripple = primary ? 0x33FFFFFF : 0x220A7D5A;
        button.setBackground(ripple(ripple,
                rounded(fill, dp(context, 12)), null));
        return button;
    }

    static View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.leftMargin = dp(context, 16);
        divider.setLayoutParams(params);
        return divider;
    }

    static void pressable(View row) {
        row.setBackground(ripple(RIPPLE,
                new ColorDrawable(CARD), null));
        row.setClickable(true);
    }

    static Drawable ripple(int color, Drawable content, Drawable mask) {
        if (Build.VERSION.SDK_INT >= 21) {
            return new RippleDrawable(ColorStateList.valueOf(color), content, mask);
        }
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(color));
        states.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(color));
        states.addState(new int[0], content == null
                ? new ColorDrawable(android.graphics.Color.TRANSPARENT) : content);
        return states;
    }

    /** Maps a free-form status message to the new state; unknown messages keep the current one. */
    interface Classifier {
        int classify(String message, int current);
    }

    static int classifyBox(String message, int current) {
        if (message.contains("音频") || message.contains("图标")) {
            return current;
        }
        if (containsAny(message, "失败", "未通过", "无效", "异常", "未响应", "未加载", "已中断", "无法绑定")) {
            return STATE_ERROR;
        }
        if (containsAny(message, "未连接", "已断开")) {
            return STATE_IDLE;
        }
        if (containsAny(message, "握手完成", "Legacy CMD_OPEN", "视频帧")) {
            return STATE_OK;
        }
        if (containsAny(message, "等待", "USB 通道已建立", "重发", "已发送", "正在")) {
            return STATE_WAIT;
        }
        return current;
    }

    static int classifyPhone(String message, int current) {
        if (message.contains("麦克风") && !message.contains("失败")) {
            return current;
        }
        if (containsAny(message, "失败", "异常")) {
            return STATE_ERROR;
        }
        if (containsAny(message, "未连接", "已断开")) {
            return STATE_IDLE;
        }
        if (containsAny(message, "已连接", "工作中")) {
            return STATE_OK;
        }
        if (containsAny(message, "等待", "已检测到", "唤醒", "连接阶段")) {
            return STATE_WAIT;
        }
        return current;
    }

    private static boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** Off-screen holder of the latest status message; the UI only shows its state as an icon. */
    static final class StatusText extends TextView {
        private final Classifier classifier;
        private int state = STATE_IDLE;
        private Runnable onChange;

        StatusText(Context context, Classifier classifier) {
            super(context);
            this.classifier = classifier;
        }

        void setOnChange(Runnable onChange) {
            this.onChange = onChange;
        }

        int getState() {
            return state;
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            if (classifier == null) {
                return;
            }
            state = classifier.classify(text == null ? "" : text.toString(), state);
            if (onChange != null) {
                onChange.run();
            }
        }
    }
}
