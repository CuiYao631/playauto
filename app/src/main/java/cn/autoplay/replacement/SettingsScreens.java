package cn.autoplay.replacement;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Settings pages: application settings, more settings and the environment check. */
final class SettingsScreens {
    static final class CheckResult {
        final boolean ok;
        final String text;

        CheckResult(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }
    }

    private final MainActivity host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private LinearLayout group;
    private static final String[] STEERING_LABELS = {
            "上一曲 / 接听", "下一曲 / 挂断", "播放 / 暂停", "Siri", "主页", "返回"
    };
    private static final int[] STEERING_COMMANDS = {10001, 10002, 203, 10003, 200, 106};
    private int learningCommand = -1;
    private int learningKey = KeyEvent.KEYCODE_UNKNOWN;
    private String learningVendor;
    private TextView steeringHint;
    private TextView overlayPermissionStatus;
    private TextView steeringLog;
    private TextView navigationLog;
    private TextView boxBluetoothName;
    private TextView boxWifiName;
    private final java.util.ArrayDeque<String> steeringLogLines = new java.util.ArrayDeque<>();
    private final TextView[] steeringBindings = new TextView[STEERING_COMMANDS.length];
    private ImageView carLogoPreview;
    private Widgets.LinkIcon bluetoothStatusIcon;
    private Widgets.LinkIcon wifiStatusIcon;
    private TextView advancedRootStatus;
    private TextView advancedUsbStatus;
    private final java.util.ArrayList<Runnable> voiceStatusRefreshers = new java.util.ArrayList<>();

    SettingsScreens(MainActivity host) {
        this.host = host;
    }

    private SharedPreferences prefs() {
        return host.getPreferences(MainActivity.MODE_PRIVATE);
    }

    private int dp(int value) {
        return Ui.dp(host, value);
    }

    // ---- pages ----

    View settingsPage() {
        LinearLayout page = newPage("设置", "环境检测", host::showEnvironmentCheck, host::showMain, true);
        host.updateConnectionStatusViews();

        section("连接与显示");
        segmentedRow("连接模式", new String[]{"自动", "手动"}, "connect_mode", 0, host::onConnectModeChanged);
        segmentedRow("图像比例", new String[]{"等比适应", "拉伸全屏"}, "video_scale_mode", 1, host::onAspectChanged);
        resolutionRow();
        String[] rates = new String[VideoOptions.FRAME_RATES.length];
        for (int i = 0; i < rates.length; i++) rates[i] = VideoOptions.FRAME_RATES[i] + "fps";
        if (prefs().getInt("decoder_mode", VideoOptions.HARDWARE) == VideoOptions.SOFTWARE) {
            infoRow("帧率", "25fps（软解，与原版一致）");
        } else {
            valueChoiceRow("帧率", "frame_rate", rates, VideoOptions.FRAME_RATES,
                    VideoPreferences.defaultFrameRate(), host::onVideoSettingsChanged);
        }
        segmentedRow("夜间模式", new String[]{"开", "关", "跟随手机"}, "night_mode", 2, host::onNightModeChanged);
        segmentedRow("驾驶模式", new String[]{"左舵", "右舵"}, "drive_side", 0, host::onBoxSettingsChanged);
        if (prefs().getInt("decoder_mode", VideoOptions.HARDWARE) == VideoOptions.SOFTWARE) {
            infoRow("显示大小（投屏 DPI）", "160（软解，与原版一致）");
        } else {
            boolean legacyDpi = prefs().getInt("screen_dpi", 0) == 320;
            valueChoiceRow("显示大小（投屏 DPI）", "screen_dpi",
                    legacyDpi ? new String[]{"自动", "120", "160", "240", "320（已保存）"}
                            : new String[]{"自动", "120", "160", "240"},
                    legacyDpi ? new int[]{0, 120, 160, 240, 320} : new int[]{0, 120, 160, 240},
                    0, host::onBoxSettingsChanged);
        }
        valueChoiceRow("解码方式", "decoder_mode", new String[]{"软解（原版 OpenH264）", "硬解"},
                new int[]{VideoOptions.SOFTWARE, VideoOptions.HARDWARE},
                VideoOptions.HARDWARE, host::onDecoderChanged);
        if (prefs().getInt("decoder_mode", VideoOptions.HARDWARE) == VideoOptions.SOFTWARE) {
            infoRow("渲染方式", "OpenGL ES 2.0（软解）");
        } else {
            segmentedRow("渲染方式", new String[]{"Surface", "Texture"},
                    "render_mode", 0, host::onRenderChanged);
        }

        section("音频");
        segmentedRow("麦克风", new String[]{"汽车", "手机", "盒子"}, "mic_type", 0, host::onMicSourceChanged);
        sliderRow("麦克风增益", 30, "mic_gain_db", 0, "dB", host::onMicGainChanged);

        section("个性化");
        carLogoRow();
        segmentedRow("主题", new String[]{"跟随系统", "浅色", "深色"}, "theme_mode", 2, host::refreshTheme);
        section("车控");
        navigationRow("方控学习", host::showSteeringLearning);
        toggleRow("音量键切换音乐", "volume_track", false, value -> { });
        toggleRow("后台控制（媒体按键）", "background_control", false, value -> host.updateBackgroundControl());

        section("其他");
        LinearLayout overlayRow = baseRow(true);
        overlayRow.addView(title("显示在其他应用上层"), grow());
        overlayPermissionStatus = Ui.text(host, "", 14, Ui.ACCENT, false);
        overlayRow.addView(overlayPermissionStatus);
        overlayRow.addView(chevron());
        overlayRow.setOnClickListener(view -> host.openOverlayPermission());
        addRow(overlayRow);
        updateOverlayPermissionStatus();
        toggleRow("开启自动启动", BootStartReceiver.PREFERENCE, false,
                host::onBootAutoStartChanged, host::showBootAutoStartHelp);
        navigationRow("更多设置", host::showMore);
        infoRow("软件版本", host.appVersion()).setOnClickListener(view -> host.onVersionTapped());
        String boxVersion = host.boxVersion();
        infoRow("盒子版本", boxVersion == null ? "未连接" : boxVersion);

        return page;
    }

    View morePage() {
        LinearLayout page = newPage("更多设置", null, null, host::showSettings);

        section("显示");
        segmentedRow("应用全屏", new String[]{"开", "关"}, "fullscreen", 0, host::onFullscreenChanged);
        toggleRow("保持屏幕常亮", "keep_screen_on", true, host::onKeepScreenOnChanged);
        toggleRow("隐藏系统导航栏", "hide_navigation", false, value -> host.onNavigationBarChanged());
        segmentedRow("字体大小", new String[]{"标准", "大", "更大"}, "font_size", 0, host::refreshTheme);

        section("交互与音频");
        toggleRow("后台连接", ConnectionService.PREFERENCE, true,
                host::onBackgroundConnectionChanged, host::showBackgroundConnectionHelp);
        toggleRow("触控回传", "touch_enabled", true, host::onTouchEnabledChanged);
        sliderRow("媒体播放增益", 100, "audio_output_gain", 100, "%", host::onOutputGainChanged);
        choiceRow("媒体延迟", "media_delay_choice",
                new String[]{"0 ms", "100 ms", "200 ms", "500 ms", "1000 ms"}, 0, () -> {
                    int[] delays = {0, 100, 200, 500, 1000};
                    prefs().edit().putInt("media_delay", delays[prefs().getInt("media_delay_choice", 0)]).apply();
                    host.onBoxSettingsChanged();
                });
        toggleRow("自动播放音乐", "auto_play_music", false, value -> host.onBoxSettingsChanged());
        toggleRow("音频通道（盒子传输模式）", "audio_transfer", false, value -> host.onBoxSettingsChanged());
        toggleRow("电话音频属性（蓝牙通话）", "use_bt_phone", false, value -> host.onBoxSettingsChanged());
        navigationRow("音频调试", this::audioDebugDialog);
        sliderRow("导航/提示音期间媒体音量", 100, "navigation_media_gain", 100, "%",
                host::onNavigationGainChanged);

        section("盒子配置（部分设置需重连手机）");
        boxBluetoothName = boxNameRow("蓝牙名称", true);
        boxWifiName = boxNameRow("Wifi名称", false);
        segmentedRow("Wifi频段", new String[]{"2.4 GHz", "5 GHz"}, "wifi_band", 1, host::onBoxSettingsChanged);
        segmentedRow("Dock栏位置", new String[]{"默认", "底部", "驾驶侧"},
                "dock_position", 0, host::onBoxSettingsChanged);

        section("诊断");
        navigationRow("错误日志", host::showErrorLog);

        if (host.advancedFeaturesUnlocked()) {
            section("高级功能");
            navigationRow("高级功能", host::showAdvanced);
        }

        Button restore = Ui.button(host, "恢复默认设置", false);
        restore.setOnClickListener(view -> new AlertDialog.Builder(host)
                .setTitle("恢复默认设置")
                .setMessage("所有设置将恢复为默认值。")
                .setPositiveButton("恢复", (dialog, which) -> host.restoreDefaults())
                .setNegativeButton("取消", null)
                .show());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(24);
        list.addView(restore, params);
        return page;
    }

    View advancedPage() {
        LinearLayout page = newPage("高级功能", null, null, host::showMore);
        section("Root 授权状态");
        LinearLayout rootCard = Ui.card(host);
        advancedRootStatus = Ui.text(host, "", 14, Ui.TEXT, false);
        rootCard.addView(advancedRootStatus);
        list.addView(rootCard);
        section("高权限操作");
        navigationRow("申请 Root 权限", host::showRootPermissionRequest);
        infoRow("系统应用身份", (host.getApplicationInfo().flags
                & (android.content.pm.ApplicationInfo.FLAG_SYSTEM
                | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 ? "是" : "否");
        navigationRow("连接详情", host::showConnectionDetails);
        section("禁用原车语音");
        vehicleVoiceRow("禁用语音按键唤醒", VehicleVoiceControl.KEY_PACKAGE,
                "禁用后，原车语音按键广播不再通过此应用拉起语音助理。"
                        + "不会自动将语音按键绑定到 PlayAuto；语音助理的唤醒词可能仍可使用。");
        vehicleVoiceRow("禁用原车语音助理", VehicleVoiceControl.ASSISTANT_PACKAGE,
                "禁用后，原车语音助理及其唤醒引擎不可用，语音按键和唤醒词均无法使用该助理。"
                        + "其他原车语音功能也可能受到影响。");
        section("USB 权限状态");
        LinearLayout usbCard = Ui.card(host);
        advancedUsbStatus = Ui.text(host, "", 13, Ui.TEXT_SECONDARY, false);
        usbCard.addView(advancedUsbStatus);
        list.addView(usbCard);
        TextView help = Ui.text(host, "进入本页不会自动请求 Root。Root 状态仅代表本次应用运行期间"
                + "最近一次检查，管理器可能随时撤销授权；再次点击申请可重新验证。"
                + "申请仅验证 Root 身份，不修改 USB 权限或系统文件。",
                13, Ui.TEXT_SECONDARY, false);
        help.setPadding(dp(8), dp(12), dp(8), dp(12));
        list.addView(help);
        updateAdvancedStatus();
        return page;
    }

    private void vehicleVoiceRow(String label, String packageName, String warning) {
        LinearLayout row = baseRow(true);
        row.addView(title(label), grow());
        Switch toggle = new Switch(host);
        toggle.setContentDescription(label);
        row.addView(toggle);
        Runnable refresh = () -> {
            try {
                android.content.pm.ApplicationInfo application = VehicleVoiceControl.application(host, packageName);
                toggle.setChecked(!application.enabled);
                toggle.setEnabled(true);
                row.setEnabled(true);
            } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
                toggle.setChecked(false);
                toggle.setEnabled(false);
                row.setEnabled(false);
                toggle.setText("未安装");
                android.util.Log.i("AutoPlayVoice", "Vehicle voice package not installed: " + packageName);
            }
        };
        voiceStatusRefreshers.add(refresh);
        refresh.run();
        View.OnClickListener change = view -> {
            boolean disable;
            try {
                disable = VehicleVoiceControl.application(host, packageName).enabled;
                toggle.setChecked(!disable);
            } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
                refresh.run();
                android.widget.Toast.makeText(host, "应用未安装：" + packageName,
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            new AlertDialog.Builder(host)
                    .setTitle(disable ? label : "恢复原车语音应用")
                    .setMessage((disable ? warning : "将重新启用此应用，恢复其运行资格。"
                            + "若另一个语音组件仍被禁用，完整语音功能可能仍不可用。")
                            + "\n\n" + packageName + "\n\n需要 Root，不卸载应用、不清除数据。"
                            + "禁用状态由系统保存，退出 PlayAuto 或恢复默认设置不会自动恢复。"
                            + "关闭此开关可重新启用，部分车机可能需要重启。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(disable ? "确认禁用" : "确认恢复", (dialog, which) -> {
                        AlertDialog progress = new AlertDialog.Builder(host)
                                .setTitle("正在修改原车语音状态")
                                .setMessage("请允许 Root 请求，最多等待 30 秒。")
                                .setCancelable(false).show();
                        VehicleVoiceControl.change(host, packageName, disable, result -> {
                            android.util.Log.i("AutoPlayVoice", result);
                            if (progress.isShowing()) progress.dismiss();
                            if (host.isFinishing() || host.isDestroyed()) return;
                            refresh.run();
                            new AlertDialog.Builder(host).setTitle("原车语音操作结果")
                                    .setMessage(result).setPositiveButton("知道了", null).show();
                        });
                    }).show();
        };
        toggle.setOnClickListener(change);
        row.setOnClickListener(change);
        addRow(row);
    }

    void updateAdvancedStatus() {
        if (advancedRootStatus != null) advancedRootStatus.setText(RootUsbAuthorization.rootStatus());
        if (advancedUsbStatus != null) advancedUsbStatus.setText(host.advancedUsbStatus());
        for (Runnable refresh : voiceStatusRefreshers) refresh.run();
    }

    View navigationPage() {
        LinearLayout page = newPage("仪表导航（开发中）", "清空日志",
                host::clearNavigationLog, host::showSettings);
        TextView help = Ui.text(host, "接收并记录盒子上报的导航 JSON，不自动向原车仪表发送广播。"
                + "请连接手机并开始导航；是否上报取决于盒子固件和导航应用。"
                + "连接初始化时发送原版 DashboardInfo=7 配置，日志记录收到的导航 JSON 和导航开始/结束消息；"
                + "配置发送失败请查看错误日志，发送成功不代表盒子已确认。更新后请拔插盒子并重新连接手机。"
                + "保留最近 50 条，最新在前，服务销毁后清空。"
                + "数据可能包含道路名、目的地等位置隐私，请谨慎分享。",
                13, Ui.TEXT_SECONDARY, false);
        help.setPadding(dp(8), dp(16), dp(8), dp(12));
        list.addView(help);
        navigationLog = Ui.text(host, "", 13, Ui.TEXT, false);
        navigationLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        navigationLog.setTextIsSelectable(true);
        navigationLog.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView logScroll = new ScrollView(host);
        logScroll.addView(navigationLog);
        list.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));
        updateNavigationLog();
        section("仪表手动测试");
        TextView warning = Ui.text(host, "仅在车辆停稳时测试。转向测试距离固定为 100 米；"
                + "点击并确认后仅发送一次，不需要连接盒子。"
                + "CarPlay 导航激活会停止原厂高德导航，结束导航会发送仪表导航停止广播。"
                + "广播提交不代表仪表已接收，实际兼容性请观察仪表。",
                13, Ui.TEXT_SECONDARY, false);
        warning.setPadding(dp(8), dp(12), dp(8), dp(12));
        list.addView(warning);
        TextView testStatus = Ui.text(host, "尚未发送测试广播", 13, Ui.TEXT_SECONDARY, false);
        testStatus.setTextIsSelectable(true);
        testStatus.setPadding(dp(8), dp(8), dp(8), dp(12));
        String[] labels = {"左转", "右转", "直行", "左掉头", "到达目的地", "结束导航",
                "CarPlay 导航激活"};
        int[] iconIds = {27, 22, 17, 25, 6, -1, -1};
        LinearLayout buttons = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 2 == 0) {
                buttons = new LinearLayout(host);
                buttons.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(buttons);
            }
            final String label = labels[i];
            final int iconId = iconIds[i];
            final String action = i == 5 ? "com.autonavi.navigation.NAVIGATION_STOPPED"
                    : i == 6 ? "carplay_navigation_activated"
                    : "com.autonavi.navigation.NAVIGATION_UPDATES";
            Button button = Ui.button(host, label, false);
            button.setMinimumHeight(dp(48));
            button.setOnClickListener(view -> confirmNavigationTest(label, action, iconId, testStatus));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            buttons.addView(button, params);
        }
        list.addView(testStatus);
        return page;
    }

    private void confirmNavigationTest(String label, String action, int iconId, TextView status) {
        String details = action + (iconId >= 0
                ? "\nnavigation_id=" + iconId + "\nnavigation_dis=100,m" : "");
        new AlertDialog.Builder(host).setTitle("仪表测试：" + label)
                .setMessage("请确认车辆已停稳。"
                        + ("carplay_navigation_activated".equals(action)
                        ? "\n此操作会停止原厂高德当前导航。" : "")
                        + "\n\n将发送一次广播：\n" + details)
                .setNegativeButton("取消", null)
                .setPositiveButton("发送", (dialog, which) -> {
                    Intent intent = new Intent(action);
                    if (iconId >= 0) {
                        intent.putExtra("navigation_id", iconId);
                        intent.putExtra("navigation_dis", "100,m");
                    }
                    try {
                        host.sendBroadcast(intent);
                        String time = new java.text.SimpleDateFormat(
                                "HH:mm:ss.SSS", java.util.Locale.ROOT).format(new java.util.Date());
                        status.setText(time + " 已提交：" + label + "\n" + details
                                + "\n无法确认仪表是否接收，请观察仪表。");
                        status.setTextColor(Ui.TEXT_SECONDARY);
                        android.util.Log.i("AutoPlayNavigation", "Manual test broadcast: " + details);
                    } catch (SecurityException exception) {
                        android.util.Log.e("AutoPlayNavigation", "Manual test broadcast denied", exception);
                        status.setText("发送失败：" + exception);
                        status.setTextColor(Ui.WARNING);
                        new AlertDialog.Builder(host).setTitle("测试广播发送失败")
                                .setMessage(exception.toString()).setPositiveButton("知道了", null).show();
                    }
                }).show();
    }

    void updateNavigationLog() {
        if (navigationLog != null) navigationLog.setText(host.navigationLogText());
    }

    View environmentPage() {
        LinearLayout page = newPage("运行环境检测", "重新检测",
                host::showEnvironmentCheck, host::showSettings);

        LinearLayout summary = Ui.card(host);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(20), dp(20), dp(20), dp(20));
        FrameHolder holder = new FrameHolder(host);
        summary.addView(holder.view, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout summaryText = new LinearLayout(host);
        summaryText.setOrientation(LinearLayout.VERTICAL);
        summaryText.setPadding(dp(16), 0, 0, 0);
        TextView summaryTitle = Ui.text(host, "检测中...", 18, Ui.TEXT, true);
        TextView summaryHint = Ui.text(host, "检测结果仅供参考", 13, Ui.TEXT_SECONDARY, false);
        summaryText.addView(summaryTitle);
        summaryText.addView(summaryHint);
        summary.addView(summaryText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        list.addView(summary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        section("检测项目");
        CheckRow mic = checkRow("麦克风检测");
        CheckRow usb = checkRow("USB检测");
        CheckRow decode = checkRow("解码检测");

        handler.postDelayed(() -> {
            CheckResult micResult = host.checkMicrophone();
            CheckResult usbResult = host.checkUsb();
            CheckResult decodeResult = checkDecoder();
            mic.show(micResult);
            usb.show(usbResult);
            decode.show(decodeResult);
            boolean allOk = micResult.ok && usbResult.ok && decodeResult.ok;
            holder.showResult(allOk);
            summaryTitle.setText(allOk ? "检测完成" : "存在问题");
            summaryTitle.setTextColor(allOk ? Ui.ACCENT : Ui.WARNING);
        }, 700);
        return page;
    }

    // ---- scaffolding ----

    private LinearLayout newPage(String title, String rightText, Runnable rightAction, Runnable back) {
        return newPage(title, rightText, rightAction, back, false);
    }

    private LinearLayout newPage(String title, String rightText, Runnable rightAction, Runnable back,
            boolean showConnectionIcons) {
        voiceStatusRefreshers.clear();
        overlayPermissionStatus = null;
        cancelSteeringLearning();
        steeringHint = null;
        steeringLog = null;
        navigationLog = null;
        boxBluetoothName = boxWifiName = null;
        java.util.Arrays.fill(steeringBindings, null);
        bluetoothStatusIcon = wifiStatusIcon = null;
        bluetoothStatusIcon = wifiStatusIcon = null;
        LinearLayout page = new LinearLayout(host);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.pageBackground());
        if (android.os.Build.VERSION.SDK_INT >= 21) page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(16), dp(8));

        View backButton = new Widgets.LinkIcon(host, Widgets.LinkIcon.BACK);
        backButton.setContentDescription("返回");
        backButton.setBackground(Ui.ripple(
                Ui.ACCENT_SOFT, null,
                Ui.oval(android.graphics.Color.WHITE, dp(48))));
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            backButton.setTooltipText("返回");
        }
        backButton.setOnClickListener(view -> back.run());
        bar.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView titleView = Ui.text(host, title, 24, Ui.TEXT, true);
        titleView.setPadding(dp(8), 0, 0, 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (showConnectionIcons) {
            bluetoothStatusIcon = new Widgets.LinkIcon(host, Widgets.LinkIcon.BLUETOOTH);
            bar.addView(bluetoothStatusIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
            wifiStatusIcon = new Widgets.LinkIcon(host, Widgets.LinkIcon.WIFI);
            wifiStatusIcon.setOnClickListener(view -> host.showConnectionDetails());
            LinearLayout.LayoutParams wifiParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            wifiParams.rightMargin = dp(8);
            bar.addView(wifiStatusIcon, wifiParams);
        }
        if (rightText != null) {
            TextView action = Ui.text(host, rightText, 14, Ui.ACCENT, true);
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(14), dp(9), dp(14), dp(9));
            action.setBackground(Ui.rounded(Ui.ACCENT_SOFT, dp(18)));
            action.setOnClickListener(view -> rightAction.run());
            bar.addView(action);
        }
        page.addView(bar);

        ScrollView scroll = new ScrollView(host);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        list = new LinearLayout(host);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(24));
        FrameLayout content = new FrameLayout(host);
        int listWidth = host.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ? Math.min(host.getResources().getDisplayMetrics().widthPixels, dp(840))
                : FrameLayout.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams listParams = new FrameLayout.LayoutParams(
                listWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        content.addView(list, listParams);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        group = null;
        return page;
    }

    void updateOverlayPermissionStatus() {
        if (overlayPermissionStatus == null) return;
        boolean allowed = AndroidCompat.canDrawOverlays(host);
        overlayPermissionStatus.setText(allowed ? "已允许" : "未允许");
        overlayPermissionStatus.setTextColor(allowed ? Ui.ACCENT : Ui.TEXT_SECONDARY);
    }

    private void section(String title) {
        TextView label = Ui.text(host, title, 13, Ui.TEXT_SECONDARY, true);
        label.setPadding(dp(8), dp(20), 0, dp(8));
        list.addView(label);
        group = Ui.card(host);
        list.addView(group, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addRow(View row) {
        if (group.getChildCount() > 0) {
            group.addView(Ui.divider(host));
        }
        group.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout baseRow(boolean clickable) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        if (clickable) {
            Ui.pressable(row);
        }
        return row;
    }

    private LinearLayout.LayoutParams grow() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private TextView title(String text) {
        return Ui.text(host, text, 16, Ui.TEXT, false);
    }

    // ---- rows ----

    private void segmentedRow(String title, String[] options, String key, int defaultIndex, Runnable onChange) {
        LinearLayout row = baseRow(false);
        row.addView(title(title), grow());

        LinearLayout control = new LinearLayout(host);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setPadding(dp(3), dp(3), dp(3), dp(3));
        control.setBackground(Ui.rounded(Ui.DIVIDER, dp(12)));
        int selected = clamp(prefs().getInt(key, defaultIndex), options.length);
        TextView[] segments = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            segments[i] = Ui.text(host, options[i], 14, Ui.TEXT_SECONDARY, false);
            segments[i].setGravity(Gravity.CENTER);
            segments[i].setPadding(dp(12), dp(7), dp(12), dp(7));
            segments[i].setOnClickListener(view -> {
                prefs().edit().putInt(key, index).apply();
                paintSegments(segments, index);
                if (onChange != null) {
                    onChange.run();
                }
            });
            control.addView(segments[i]);
        }
        paintSegments(segments, selected);
        row.addView(control);
        addRow(row);
    }

    private void paintSegments(TextView[] segments, int selected) {
        for (int i = 0; i < segments.length; i++) {
            boolean on = i == selected;
            segments[i].setSelected(on);
            segments[i].setTextColor(on ? Ui.ON_ACCENT : Ui.TEXT_SECONDARY);
            segments[i].setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            segments[i].setBackground(on ? Ui.rounded(Ui.ACCENT, dp(9)) : null);
        }
    }

    private void choiceRow(String title, String key, String[] choices, int defaultIndex) {
        choiceRow(title, key, choices, defaultIndex, () -> { });
    }

    private void resolutionRow() {
        String[] choices = host.resolutionChoices();
        String selected = host.requestedVideoSizeLabel();
        LinearLayout row = baseRow(true);
        row.addView(title("分辨率"), grow());
        TextView value = Ui.text(host, selected, 15, Ui.ACCENT, true);
        row.addView(value);
        row.addView(chevron());
        row.setOnClickListener(view -> new AlertDialog.Builder(host)
                .setTitle("分辨率")
                .setSingleChoiceItems(choices, java.util.Arrays.asList(choices)
                                .indexOf(host.requestedVideoSizeLabel()), (dialog, which) -> {
                    prefs().edit().putString("video_size", choices[which]).remove("resolution").apply();
                    value.setText(choices[which]);
                    dialog.dismiss();
                    host.onVideoSettingsChanged();
                }).setNegativeButton("取消", null).show());
        addRow(row);
    }

    private void valueChoiceRow(String title, String key, String[] choices, int[] values,
            int defaultValue, Runnable changed) {
        LinearLayout row = baseRow(true);
        row.addView(title(title), grow());
        int selected = valueIndex(values, prefs().getInt(key, defaultValue));
        TextView value = Ui.text(host, choices[selected], 15, Ui.ACCENT, true);
        row.addView(value);
        row.addView(chevron());
        row.setOnClickListener(view -> new AlertDialog.Builder(host)
                .setTitle(title)
                .setSingleChoiceItems(choices, valueIndex(values, prefs().getInt(key, defaultValue)),
                        (dialog, which) -> {
                            prefs().edit().putInt(key, values[which]).apply();
                            value.setText(choices[which]);
                            dialog.dismiss();
                            changed.run();
                        }).setNegativeButton("取消", null).show());
        addRow(row);
    }

    private static int valueIndex(int[] values, int selected) {
        for (int i = 0; i < values.length; i++) if (values[i] == selected) return i;
        throw new IllegalArgumentException("Unsupported choice value: " + selected);
    }

    private void choiceRow(String title, String key, String[] choices, int defaultIndex, Runnable changed) {
        LinearLayout row = baseRow(true);
        row.addView(title(title), grow());
        TextView value = Ui.text(host,
                choices[clamp(prefs().getInt(key, defaultIndex), choices.length)],
                15, Ui.ACCENT, true);
        row.addView(value);
        row.addView(chevron());
        row.setOnClickListener(view -> new AlertDialog.Builder(host)
                .setTitle(title)
                .setSingleChoiceItems(choices, clamp(prefs().getInt(key, defaultIndex), choices.length),
                        (dialog, which) -> {
                            prefs().edit().putInt(key, which).apply();
                            value.setText(choices[which]);
                            dialog.dismiss();
                            changed.run();
                        })
                .setNegativeButton("取消", null)
                .show());
        addRow(row);
    }

    private void nameDialog(boolean bluetooth) {
        String key = bluetooth ? "box_bt_name" : "box_wifi_name";
        EditText input = new EditText(host);
        input.setSingleLine();
        input.setText(prefs().getString(key, ""));
        input.setHint("名称最多 15 个 UTF-8 字节");
        AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle(bluetooth ? "盒子蓝牙名称" : "盒子 Wifi 名称")
                .setView(input).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            try {
                BoxSetting.name(bluetooth, name);
            } catch (IllegalArgumentException exception) {
                input.setError(exception.getMessage());
                return;
            }
            prefs().edit().putString(key, name).apply();
            host.onBoxSettingsChanged();
            dialog.dismiss();
        }));
        dialog.show();
    }

    void updateBoxNames() {
        if (boxBluetoothName != null) boxBluetoothName.setText(host.boxNameText(true));
        if (boxWifiName != null) boxWifiName.setText(host.boxNameText(false));
    }

    private TextView boxNameRow(String label, boolean bluetooth) {
        LinearLayout row = baseRow(true);
        row.addView(title(label), grow());
        TextView value = Ui.text(host, host.boxNameText(bluetooth), 14, Ui.TEXT_SECONDARY, false);
        value.setSingleLine();
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setMaxWidth(dp(220));
        value.setGravity(Gravity.END);
        row.addView(value);
        row.addView(chevron());
        row.setOnClickListener(view -> nameDialog(bluetooth));
        addRow(row);
        return value;
    }

    private void audioDebugDialog() {
        new AlertDialog.Builder(host).setTitle("音频调试")
                .setItems(new String[]{"播放车机测试音", "播放手机音乐", "暂停手机音乐", "下一曲", "上一曲"},
                        (dialog, which) -> {
                            if (which == 0) {
                                host.playTestTone();
                            } else {
                                host.sendMediaCommand(new int[]{0, 201, 202, 204, 205}[which]);
                            }
                        }).setNegativeButton("关闭", null).show();
    }

    View steeringPage() {
        SteeringProtocol.migrate(prefs());
        String[] steeringLabels = STEERING_LABELS.clone();
        if (SteeringProtocol.vendorEnabled(prefs())) {
            steeringLabels[0] = "上一曲 / 挂断";
            steeringLabels[1] = "下一曲 / 接听";
        }
        LinearLayout page = newPage("方控学习", "取消学习", this::cancelSteeringLearning, host::showSettings);
        section("协议");
        choiceRow("方控协议", SteeringProtocol.PREFERENCE, SteeringProtocol.LABELS, 0,
                host::onSteeringProtocolChanged);
        steeringHint = Ui.text(host, "选择下方功能，再按一次方向盘按键", 15, Ui.TEXT, true);
        steeringHint.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.addView(steeringHint);
        TextView help = Ui.text(host,
                SteeringProtocol.vendorEnabled(prefs())
                        ? "长安 CS35Plus：支持 Android 按键和厂家 NEXT / PRE / VR / MAIN 广播。MAIN 为触摸按钮，按下即可学习；其他键按下并松开。主页、返回控制手机投屏，不会阻止车机原有动作。"
                        : "默认协议：仅支持 Android 按键。选择下方功能，再按下并松开按键。两种协议的学习绑定独立保存。",
                13, Ui.TEXT_SECONDARY, false);
        help.setPadding(dp(8), 0, dp(8), dp(8));
        list.addView(help);
        int columns = host.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 3 : 2;
        LinearLayout tileRow = null;
        for (int i = 0; i < STEERING_COMMANDS.length; i++) {
            final int index = i;
            if (i % columns == 0) {
                tileRow = new LinearLayout(host);
                tileRow.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(tileRow, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout row = Ui.card(host);
            if (android.os.Build.VERSION.SDK_INT >= 21) row.setElevation(0);
            row.setMinimumHeight(dp(94));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setGravity(Gravity.CENTER);
            row.setBackground(Ui.ripple(
                    Ui.RIPPLE, Ui.rounded(Ui.CARD, dp(12)), null));
            LinearLayout labels = new LinearLayout(host);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.CENTER);
            TextView label = Ui.text(host, steeringLabels[i], 16, Ui.TEXT, true);
            label.setGravity(Gravity.CENTER);
            labels.addView(label);
            steeringBindings[i] = Ui.text(host, "", 13, Ui.TEXT_SECONDARY, false);
            steeringBindings[i].setGravity(Gravity.CENTER);
            steeringBindings[i].setPadding(0, dp(6), 0, 0);
            labels.addView(steeringBindings[i]);
            row.addView(labels);
            row.setContentDescription("学习" + steeringLabels[i]);
            row.setOnClickListener(view -> {
                learningCommand = STEERING_COMMANDS[index];
                learningKey = KeyEvent.KEYCODE_UNKNOWN;
                learningVendor = null;
                android.util.Log.i("AutoPlayKeys", "learn_start button=" + steeringLabels[index]
                        + " action=" + learningCommand);
                steeringHint.setText("正在学习「" + steeringLabels[index] + "」，请按下并松开方控按键");
                steeringHint.setTextColor(Ui.ACCENT);
            });
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            tileParams.setMargins(dp(4), dp(4), dp(4), dp(4));
            tileRow.addView(row, tileParams);
        }
        Button clear = Ui.button(host, "清除已学习按键", false);
        clear.setOnClickListener(view -> {
            cancelSteeringLearning();
            new AlertDialog.Builder(host).setTitle("清除方控绑定")
                    .setMessage("清除当前协议的所有方控绑定？其他协议和设置不受影响。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清除", (dialog, which) -> {
                        SharedPreferences.Editor editor = prefs().edit();
                        for (String key : prefs().getAll().keySet()) {
                            if (key.startsWith(SteeringProtocol.androidPrefix(prefs()))
                                    || (SteeringProtocol.vendorEnabled(prefs())
                                    && key.startsWith(SteeringProtocol.vendorPrefix()))) editor.remove(key);
                        }
                        editor.apply();
                        android.util.Log.i("AutoPlayKeys", "bindings_cleared");
                        updateSteeringBindings();
                        steeringHint.setText("已清除方控绑定，请选择功能重新学习");
                    }).show();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(20);
        list.addView(clear, params);
        section("按键日志");
        LinearLayout logCard = Ui.card(host);
        if (android.os.Build.VERSION.SDK_INT >= 21) logCard.setElevation(0);
        logCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        Button clearLog = Ui.button(host, "清除日志", false);
        clearLog.setMinimumHeight(dp(48));
        clearLog.setOnClickListener(view -> {
            clearSteeringLog();
        });
        LinearLayout.LayoutParams clearLogParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clearLogParams.gravity = Gravity.END;
        clearLogParams.bottomMargin = dp(8);
        logCard.addView(clearLog, clearLogParams);
        logCard.addView(Ui.text(host,
                SteeringProtocol.vendorEnabled(prefs())
                        ? "无需学习即可检测按键及广播类型，当前仅检测、不拦截。厂家广播需手动学习，仅应用前台且手机已连接时控制；未知键及长按状态不执行。最新在上，保留 50 条。"
                        : "无需学习即可检测 Android 按键；默认协议不接收厂家广播。最新在上，保留 50 条。",
                12, Ui.TEXT_SECONDARY, false));
        ScrollView logScroll = new ScrollView(host);
        steeringLog = Ui.text(host, "", 12, Ui.TEXT, false);
        steeringLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        steeringLog.setPadding(0, dp(10), 0, 0);
        logScroll.addView(steeringLog);
        logCard.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));
        list.addView(logCard);
        updateSteeringLog();
        updateSteeringBindings();
        return page;
    }

    void cancelSteeringLearning() {
        if (learningCommand >= 0) {
            android.util.Log.i("AutoPlayKeys", "learn_end button=" + selectedSteeringActionName(learningCommand));
        }
        learningCommand = -1;
        learningKey = KeyEvent.KEYCODE_UNKNOWN;
        learningVendor = null;
        if (steeringHint != null) {
            steeringHint.setText("选择下方功能，再按一次方向盘按键");
            steeringHint.setTextColor(Ui.TEXT);
        }
    }

    boolean handleSteeringKey(KeyEvent event) {
        if (learningCommand < 0) return false;
        if (learningVendor != null) return true;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME
                || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_APP_SWITCH
                || code == KeyEvent.KEYCODE_UNKNOWN) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            learningKey = code;
        } else if (event.getAction() == KeyEvent.ACTION_UP && code == learningKey) {
            if (event.isCanceled()) {
                cancelSteeringLearning();
                return true;
            }

            prefs().edit().putInt(SteeringProtocol.androidPrefix(prefs()) + code, learningCommand).apply();
            android.util.Log.i("AutoPlayKeys", "learn_bound button=" + selectedSteeringActionName(learningCommand)
                    + " key=" + KeyEvent.keyCodeToString(code) + " keyCode=" + code
                    + " scanCode=" + event.getScanCode());
            cancelSteeringLearning();
            updateSteeringBindings();
            steeringHint.setText("学习成功：" + KeyEvent.keyCodeToString(code));
            steeringHint.setTextColor(Ui.ACCENT);
        }
        return true;
    }

    boolean handleVendorKey(String code, String state, boolean press) {
        if (!SteeringProtocol.vendorEnabled(prefs())) return false;
        if (learningCommand < 0) return false;
        if (learningKey != KeyEvent.KEYCODE_UNKNOWN) return true;
        if (press && learningVendor == null) learningVendor = code;
        if (!code.equals(learningVendor)) return true;
        if (("MAIN".equals(code) && press) || (!"MAIN".equals(code) && "UP".equals(state))) {
            prefs().edit().putInt(SteeringProtocol.vendorPrefix() + code, learningCommand).apply();
            android.util.Log.i("AutoPlayKeys", "learn_bound vendor=" + code + " action=" + learningCommand);
            cancelSteeringLearning();
            updateSteeringBindings();
            steeringHint.setText("学习成功：厂家 " + code);
            steeringHint.setTextColor(Ui.ACCENT);
        }
        return true;
    }

    private String selectedSteeringActionName(int action) {
        return steeringActionName(SteeringProtocol.playbackAction(prefs(), action));
    }

    static String steeringActionName(int action) {
        if (action == 10004) return "上一曲 / 挂断";
        if (action == 10005) return "下一曲 / 接听";
        for (int i = 0; i < STEERING_COMMANDS.length; i++) {
            if (STEERING_COMMANDS[i] == action) return STEERING_LABELS[i];
        }
        if (action == 201) return "播放";
        if (action == 202) return "暂停";
        if (action == 204) return "下一曲";
        if (action == 205) return "上一曲";
        return "未映射(" + action + ")";
    }

    static void logSteeringKey(String source, KeyEvent event, int action) {
        android.util.Log.i("AutoPlayKeys", "key source=" + source
                + " event=" + (event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN"
                        : event.getAction() == KeyEvent.ACTION_UP ? "UP" : "MULTIPLE")
                + " key=" + KeyEvent.keyCodeToString(event.getKeyCode())
                + " keyCode=" + event.getKeyCode() + " scanCode=" + event.getScanCode()
                + " repeat=" + event.getRepeatCount() + " deviceId=" + event.getDeviceId()
                + " action=" + action);
    }

    void recordSteeringKey(KeyEvent event, int action) {
        String phase = event.getAction() == KeyEvent.ACTION_DOWN ? "按下"
                : event.getAction() == KeyEvent.ACTION_UP ? "松开" : "连续";
        String time = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        appendSteeringLog(time + " [Android按键] " + phase + " "
                + KeyEvent.keyCodeToString(event.getKeyCode())
                + "\n键码=" + event.getKeyCode() + " 扫描码=" + event.getScanCode()
                + " 重复=" + event.getRepeatCount() + " 设备=" + event.getDeviceId()
                + "\n功能：" + selectedSteeringActionName(action)
                + (learningCommand >= 0 ? " | 正在学习：" + selectedSteeringActionName(learningCommand) : ""));
    }

    void recordVendorKey(String code, String state, boolean ordered, boolean noAbort) {
        String safeCode = vendorLogValue(code);
        String safeState = vendorLogValue(state);
        android.util.Log.i("AutoPlayKeys", "vendor_broadcast Key_code=" + safeCode + " Key_state=" + safeState);
        String time = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        int action = VendorKeys.supported(code) ? SteeringProtocol.vendorCommand(prefs(), code) : -1;
        appendSteeringLog(time + " [厂家广播]\nKey_code=" + safeCode
                + "\nKey_state=" + safeState
                + "\n广播类型：" + (ordered ? "有序广播" : "普通广播")
                + "\n禁止中止标记：" + (noAbort ? "是" : "否")
                + "\n" + (!ordered ? "普通广播无法通过中止阻止其他软件接收"
                        : noAbort ? "发送方禁止中止，不能依靠 abortBroadcast 拦截"
                        : "可进一步测试中止后续接收；不保证最先收到或阻止系统动作")
                + "\n当前行为：仅检测，不拦截"
                + "\n功能：" + selectedSteeringActionName(action));
    }

    private String vendorLogValue(String value) {
        String bounded = value.length() > 128 ? value.substring(0, 128) + "..." : value;
        return bounded.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    void clearSteeringLog() {
        steeringLogLines.clear();
        updateSteeringLog();
    }

    private void appendSteeringLog(String line) {
        steeringLogLines.addFirst(line);
        while (steeringLogLines.size() > 50) steeringLogLines.removeLast();
        updateSteeringLog();
    }

    private void updateSteeringLog() {
        if (steeringLog == null) return;
        StringBuilder text = new StringBuilder();
        for (String line : steeringLogLines) {
            if (text.length() > 0) text.append("\n\n");
            text.append(line);
        }
        steeringLog.setText(text.length() == 0 ? "等待按键事件..." : text);
    }

    private void updateSteeringBindings() {
        for (int i = 0; i < STEERING_COMMANDS.length; i++) {
            StringBuilder keys = new StringBuilder();
            for (java.util.Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
                if (SteeringProtocol.vendorEnabled(prefs())
                        && entry.getKey().startsWith(SteeringProtocol.vendorPrefix())
                        && Integer.valueOf(STEERING_COMMANDS[i]).equals(entry.getValue())) {
                    if (keys.length() > 0) keys.append("、");
                    keys.append("厂家 ").append(entry.getKey().substring(SteeringProtocol.vendorPrefix().length()));
                }
                if (entry.getKey().startsWith(SteeringProtocol.androidPrefix(prefs()))
                        && Integer.valueOf(STEERING_COMMANDS[i]).equals(entry.getValue())) {
                    if (keys.length() > 0) keys.append("、");
                    keys.append(KeyEvent.keyCodeToString(Integer.parseInt(
                            entry.getKey().substring(SteeringProtocol.androidPrefix(prefs()).length()))));
                }
            }
            if (steeringBindings[i] != null) {
                steeringBindings[i].setText(keys.length() == 0 ? "点击学习" : "已绑定：" + keys);
            }
        }
    }

    private void navigationRow(String title, Runnable action) {
        LinearLayout row = baseRow(true);
        row.addView(title(title), grow());
        row.addView(chevron());
        row.setOnClickListener(view -> action.run());
        addRow(row);
    }

    private LinearLayout infoRow(String title, String value) {
        LinearLayout row = baseRow(false);
        row.addView(title(title), grow());
        TextView valueView = Ui.text(host, value, 15, Ui.TEXT_SECONDARY, false);
        valueView.setGravity(Gravity.END);
        row.addView(valueView);
        addRow(row);
        return row;
    }

    void updateConnectionStatus(boolean bluetooth, boolean wifi) {
        if (bluetoothStatusIcon != null) {
            bluetoothStatusIcon.setOn(bluetooth);
            bluetoothStatusIcon.setContentDescription(bluetooth ? "蓝牙已连接" : "蓝牙未连接");
        }
        if (wifiStatusIcon != null) {
            wifiStatusIcon.setOn(wifi);
            wifiStatusIcon.setContentDescription((wifi ? "Wi-Fi已连接" : "Wi-Fi未连接") + "，查看连接详情");
        }
    }

    private void toggleRow(String title, String key, boolean defaultValue, MainActivity.BooleanListener listener) {
        toggleRow(title, key, defaultValue, listener, null);
    }

    private void toggleRow(String title, String key, boolean defaultValue,
            MainActivity.BooleanListener listener, Runnable help) {
        LinearLayout row = baseRow(true);
        if (help == null) {
            row.addView(title(title), grow());
        } else {
            LinearLayout label = new LinearLayout(host);
            label.setOrientation(LinearLayout.HORIZONTAL);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.addView(title(title));

            FrameLayout helpButton = new FrameLayout(host);
            helpButton.setContentDescription(BootStartReceiver.PREFERENCE.equals(key)
                    ? "开机启动说明" : title + "说明");
            Ui.pressable(helpButton);
            TextView icon = Ui.text(host, "!", 13, Ui.TEXT_SECONDARY, true);
            icon.setGravity(Gravity.CENTER);
            icon.setIncludeFontPadding(false);
            android.graphics.drawable.GradientDrawable circle =
                    new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(android.graphics.Color.TRANSPARENT);
            circle.setStroke(dp(1), Ui.TEXT_SECONDARY);
            icon.setBackground(circle);
            helpButton.addView(icon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            helpButton.setOnClickListener(view -> help.run());
            label.addView(helpButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
            row.addView(label, grow());
        }
        Switch toggle = new Switch(host);
        toggle.setChecked(prefs().getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            prefs().edit().putBoolean(key, checked).apply();
            listener.onChanged(checked);
        });
        row.addView(toggle);
        row.setOnClickListener(view -> toggle.toggle());
        addRow(row);
    }

    private void sliderRow(String title, int max, String key, int defaultValue, String unit,
            MainActivity.IntListener listener) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(6));

        LinearLayout top = new LinearLayout(host);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(title(title), grow());
        int current = prefs().getInt(key, defaultValue);
        TextView chip = Ui.text(host, current + " " + unit, 13, Ui.ACCENT, true);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        chip.setBackground(Ui.rounded(Ui.ACCENT_SOFT, dp(10)));
        top.addView(chip);
        row.addView(top);

        SeekBar bar = new SeekBar(host);
        bar.setMax(max);
        bar.setProgress(current);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                chip.setText(progress + " " + unit);
                if (fromUser) {
                    prefs().edit().putInt(key, progress).apply();
                    listener.onChanged(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addRow(row);
    }

    private void carLogoRow() {
        LinearLayout row = baseRow(true);
        row.addView(title("汽车图标"), grow());
        carLogoPreview = new ImageView(host);
        carLogoPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(carLogoPreview, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.addView(chevron());
        row.setOnClickListener(view -> host.showCarLogoPicker());
        addRow(row);
        refreshCarLogo();
    }

    void refreshCarLogo() {
        if (carLogoPreview != null) {
            Bitmap bitmap = host.carLogoBitmap();
            carLogoPreview.setImageBitmap(bitmap);
        }
    }

    private TextView chevron() {
        TextView arrow = Ui.text(host, "›", 24, Ui.TEXT_DISABLED, false);
        arrow.setPadding(dp(10), 0, 0, 0);
        return arrow;
    }

    private static int clamp(int index, int length) {
        return Math.max(0, Math.min(index, length - 1));
    }

    // ---- environment check ----

    private final class FrameHolder {
        final android.widget.FrameLayout view;
        private final ProgressBar spinner;
        private final TextView mark;

        FrameHolder(android.content.Context context) {
            view = new android.widget.FrameLayout(context);
            view.setBackground(Ui.rounded(Ui.ACCENT_SOFT, dp(32)));
            spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
            AndroidCompat.tintSpinner(spinner);
            spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.addView(spinner, new android.widget.FrameLayout.LayoutParams(
                    dp(28), dp(28), Gravity.CENTER));
            mark = Ui.text(context, "", 30, Ui.ACCENT, true);
            mark.setGravity(Gravity.CENTER);
            mark.setVisibility(View.GONE);
            view.addView(mark, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        }

        void showResult(boolean ok) {
            spinner.setVisibility(View.GONE);
            view.setBackground(Ui.rounded(ok ? Ui.ACCENT_SOFT : Ui.DIVIDER, dp(32)));
            mark.setText(ok ? "✓" : "!");
            mark.setTextColor(ok ? Ui.ACCENT : Ui.WARNING);
            mark.setVisibility(View.VISIBLE);
        }
    }

    private final class CheckRow {
        private final TextView badge;
        private final TextView detail;

        CheckRow(TextView badge, TextView detail) {
            this.badge = badge;
            this.detail = detail;
        }

        void show(CheckResult result) {
            badge.setText(result.ok ? "✓" : "✗");
            badge.setTextColor(result.ok ? Ui.ACCENT : Ui.WARNING);
            badge.setBackground(Ui.rounded(result.ok ? Ui.ACCENT_SOFT : Ui.DIVIDER, dp(16)));
            detail.setText(result.text);
        }
    }

    private CheckRow checkRow(String title) {
        LinearLayout row = baseRow(false);
        row.setMinimumHeight(dp(64));
        TextView badge = Ui.text(host, "…", 16, Ui.TEXT_DISABLED, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.rounded(Ui.DIVIDER, dp(16)));
        row.addView(badge, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout labels = new LinearLayout(host);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, 0, 0);
        labels.addView(title(title));
        TextView detail = Ui.text(host, "检测中", 13, Ui.TEXT_SECONDARY, false);
        labels.addView(detail);
        row.addView(labels, grow());
        addRow(row);
        return new CheckRow(badge, detail);
    }

    private CheckResult checkDecoder() {
        try {
            int bestWidth = 0;
            int bestHeight = 0;
            boolean found = false;
            for (MediaCodecInfo info : AndroidCompat.decoders()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!AndroidCompat.AVC_MIME_TYPE.equalsIgnoreCase(type)) {
                        continue;
                    }
                    found = true;
                    int[] size = AndroidCompat.videoSize(info, type);
                    if (size == null) {
                        continue;
                    }
                    int width = size[0];
                    int height = size[1];
                    if ((long) width * height > (long) bestWidth * bestHeight) {
                        bestWidth = width;
                        bestHeight = height;
                    }
                }
            }
            if (bestWidth == 0) {
                if (found) return new CheckResult(true, "支持 H.264；此系统无法查询分辨率上限");
                return new CheckResult(false, "未找到 H.264 解码器");
            }
            return new CheckResult(true, "解码能力: " + bestWidth + "x" + bestHeight);
        } catch (RuntimeException exception) {
            return new CheckResult(false, "解码器查询失败");
        }
    }
}
