package cn.autoplay.replacement;

import android.content.Context;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 全屏升级进度页，盒子固件升级与软件更新共用同一套视觉与交互。
 *
 * <p>布局自上而下：标题 → 警告提示 → 百分比 → 进度条 → 状态文字 → 补充说明 → 退出按钮。
 * 退出按钮默认隐藏，仅在允许离开的阶段显示。
 */
final class UpgradeProgressPage {

    private final Context context;
    private final LinearLayout root;
    private final TextView percentView;
    private final ProgressBar bar;
    private final TextView statusView;
    private final TextView hintView;
    private final Button exitButton;
    private final boolean landscape;

    UpgradeProgressPage(Context context, String title, String warning) {
        this.context = context;
        this.landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackground(Ui.pageBackground());
        int pad = dp(landscape ? 40 : 28);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = Ui.text(context, title, landscape ? 26 : 22, Ui.TEXT, true);
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView, wrapParams(0, landscape ? 6 : 10));

        TextView warningView = Ui.text(context, warning, 13, Ui.WARNING, false);
        warningView.setGravity(Gravity.CENTER);
        root.addView(warningView, wrapParams(0, landscape ? 18 : 26));

        percentView = Ui.text(context, "0%", landscape ? 46 : 40, Ui.ACCENT, true);
        percentView.setGravity(Gravity.CENTER);
        root.addView(percentView, wrapParams(0, landscape ? 12 : 18));

        bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
        int side = dp(landscape ? 120 : 24);
        barParams.leftMargin = side;
        barParams.rightMargin = side;
        barParams.bottomMargin = dp(landscape ? 16 : 22);
        root.addView(bar, barParams);

        statusView = Ui.text(context, "", 16, Ui.TEXT, false);
        statusView.setGravity(Gravity.CENTER);
        root.addView(statusView, wrapParams(0, 10));

        hintView = Ui.text(context, "", 13, Ui.TEXT_SECONDARY, false);
        hintView.setGravity(Gravity.CENTER);
        hintView.setVisibility(View.GONE);
        root.addView(hintView, wrapParams(0, 16));

        exitButton = Ui.button(context, "退出", false);
        exitButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams exitParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        exitParams.topMargin = dp(8);
        root.addView(exitButton, exitParams);
    }

    View root() {
        return root;
    }

    /** 0~100，超范围会被裁剪。 */
    void setProgress(int percent) {
        int value = Math.max(0, Math.min(100, percent));
        bar.setProgress(value);
        percentView.setText(value + "%");
    }

    /**
     * 显示不确定进度（例如等待安装器响应）。此时百分比文字保留最后数值，
     * 但进度条切换为循环动画。
     */
    void setIndeterminate(boolean indeterminate) {
        bar.setIndeterminate(indeterminate);
    }

    void setPercentText(CharSequence text) {
        percentView.setText(text);
    }

    void setStatus(CharSequence text, int color) {
        statusView.setText(text);
        statusView.setTextColor(color);
    }

    void setHint(CharSequence text) {
        hintView.setText(text);
        hintView.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    void setHintColor(int color) {
        hintView.setTextColor(color);
    }

    void setExitVisible(boolean visible) {
        exitButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    void setExitText(CharSequence text) {
        exitButton.setText(text);
    }

    void setOnExit(Runnable action) {
        exitButton.setOnClickListener(view -> {
            if (action != null) action.run();
        });
    }

    private LinearLayout.LayoutParams wrapParams(int horizontal, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(horizontal);
        params.rightMargin = dp(horizontal);
        params.bottomMargin = dp(bottom);
        return params;
    }

    private int dp(int value) {
        return Ui.dp(context, value);
    }
}
