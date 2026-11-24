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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** 按键日志开关，默认关闭。 */
    static final String STEERING_LOG_PREFERENCE = "steering_log";
    private int learningCommand = -1;
    private int learningKey = KeyEvent.KEYCODE_UNKNOWN;
    /** 学习时按下按键的时刻，用于区分单击/长按槽位。0 表示未按下。 */
    private long learningDownTime;
    /** 学习厂家广播时按下时刻。 */
    private long learningVendorDownTime;
    private String learningVendor;
    private TextView steeringHint;
    private TextView overlayPermissionStatus;
    private TextView steeringLog;
    private LinearLayout steeringLogCard;
    private TextView navigationLog;
    private TextView dashboardStatus;
    private TextView boxBluetoothName;
    private TextView boxWifiName;
    private final java.util.ArrayDeque<String> steeringLogLines = new java.util.ArrayDeque<>();
    private final TextView[] steeringBindings = new TextView[STEERING_COMMANDS.length];
    private ImageView carLogoPreview;
    private Widgets.LinkIcon bluetoothStatusIcon;
    private Widgets.LinkIcon wifiStatusIcon;
    private TextView advancedRootStatus;
    private TextView advancedUsbStatus;

    /** 仪表导航调试的协议状态机；随 SettingsScreens 存活，跨页面重建保留。 */
    private final ClusterNavi clusterNavi = new ClusterNavi();
    /** 当前选中的仪表图标 ID，默认左转（规范 §6.1，已实车验证）。 */
    private int clusterNaviIconId = 27;
    private TextView clusterNaviStatus;
    private TextView clusterNaviIconValue;
    private TextView clusterNaviStateLabel;
    private EditText clusterNaviDistance;

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

        section("显示");
        segmentedRow("图像比例", new String[]{"等比适应", "拉伸全屏"}, "video_scale_mode", 1, host::onAspectChanged);
        resolutionRow();
        int[] rates = VideoOptions.SELECTABLE_FRAME_RATES;
        String[] rateLabels = new String[rates.length];
        for (int i = 0; i < rates.length; i++) rateLabels[i] = rates[i] + "fps";
        if (prefs().getInt("decoder_mode", VideoOptions.HARDWARE) == VideoOptions.SOFTWARE) {
            infoRow("帧率", "25fps（软解，与原版一致）");
        } else {
            valueChoiceRow("帧率", "frame_rate", rateLabels, rates,
                    VideoOptions.normalizeFrameRate(VideoPreferences.defaultFrameRate()),
                    host::onVideoSettingsChanged);
        }
        // 只保留「跟随手机」与「大灯联动」，偏好值沿用 2 / 3。
        segmentedValueRow("夜间模式", new String[]{"跟随手机", "大灯联动"}, "night_mode",
                new int[]{2, 3}, 0, host::onNightModeChanged);
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

        section("副屏（仪表投射）");
        toggleRow("副屏悬浮窗", SecondaryScreenOverlay.PREF_ENABLED, false,
                host::onSecondaryScreenChanged, host::showSecondaryScreenHelp);
        segmentedRow("副屏大小", new String[]{"小", "中", "大"},
                SecondaryScreenOverlay.PREF_SCALE, 1, host::onSecondaryScreenScaleChanged);
        navigationRow("仪表导航调试", host::showNavigationLog);

        section("个性化");
        carLogoRow();
        segmentedRow("主题", new String[]{"跟随系统", "浅色", "深色"}, "theme_mode", 2, host::refreshTheme);
        section("车控");
        navigationRow("方控学习", host::showSteeringLearning);
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
        navigationRow("检查更新", host::showUpdateCheck);
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
        sliderRow("麦克风增益", 30, "mic_gain_db", 0, "dB", host::onMicGainChanged);
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

        section("调试");
        toggleRow("盒子指令日志窗口", BoxLogOverlay.PREF_ENABLED, false,
                host::onBoxLogOverlayChanged, host::showBoxLogOverlayHelp);
        navigationRow("清空盒子指令日志", host::clearBoxCommandLog);
        toggleRow("大灯模拟悬浮窗", HeadlightTestOverlay.PREF_ENABLED, false,
                host::onHeadlightTestOverlayChanged, host::showHeadlightTestOverlayHelp);
        navigationRow("MCU 大灯探测", host::showMcuProbe);

        section("盒子配置（部分设置需重连手机）");
        boxBluetoothName = boxNameRow("蓝牙名称", true);
        boxWifiName = boxNameRow("Wifi名称", false);
        segmentedRow("Wifi频段", new String[]{"2.4 GHz", "5 GHz"}, "wifi_band", 1, host::onBoxSettingsChanged);
        segmentedRow("Dock栏位置", new String[]{"默认", "底部", "驾驶侧"},
                "dock_position", 0, host::onBoxSettingsChanged);
        toggleRow("升级成功后重启盒子", "reboot_after_upgrade", true,
                value -> host.onBoxSettingsChanged(), host::showRebootAfterUpgradeHelp);
        navigationRow("立即重启盒子", host::onRebootBoxTapped);
        navigationRow("断开当前连接的手机", host::onManualDisconnectTapped);
        navigationRow("指定回连手机", host::showAutoConnectDevicePicker);
        navigationRow("删除已配对手机", host::showPairedDeviceRemoval);

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
        advancedUsbStatus = Ui.text(host, "", 13, Ui.TEXT, false);
        advancedUsbStatus.setTextIsSelectable(true);
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
        if (advancedUsbStatus != null) advancedUsbStatus.setText(host.connectionDiagnostics());

        for (Runnable refresh : voiceStatusRefreshers) refresh.run();
    }

    View navigationPage() {
        LinearLayout page = newPage("仪表导航", "清空日志",
                host::clearNavigationLog, host::showSettings);
        TextView help = Ui.text(host, "上半部分接收并记录盒子上报的导航 JSON（来自盒子，只做记录）；"
                + "下半部分「仪表手动测试」用于把导航投到原车仪表，两者互相独立。"
                + "请连接手机并开始导航；是否上报取决于盒子固件和导航应用。"
                + "连接初始化时发送原版 DashboardInfo=7 配置，日志记录收到的导航 JSON 和导航开始/结束消息；"
                + "配置发送失败请查看错误日志，发送成功不代表盒子已确认。更新后请拔插盒子并重新连接手机。"
                + "保留最近 50 条，最新在前，服务销毁后清空。"
                + "数据可能包含道路名、目的地等位置隐私，请谨慎分享。",
                13, Ui.TEXT_SECONDARY, false);
        help.setPadding(dp(8), dp(16), dp(8), dp(12));
        list.addView(help);
        dashboardStatus = Ui.text(host, "", 14, Ui.TEXT, false);
        dashboardStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        dashboardStatus.setTextIsSelectable(true);
        LinearLayout statusCard = Ui.card(host);
        statusCard.addView(dashboardStatus);
        list.addView(statusCard);
        navigationLog = Ui.text(host, "", 13, Ui.TEXT, false);
        navigationLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        navigationLog.setTextIsSelectable(true);
        navigationLog.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView logScroll = new ScrollView(host);
        logScroll.addView(navigationLog);
        list.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));
        updateNavigationLog();
        buildClusterNaviDebugSection();
        return page;
    }

    /**
     * 仪表导航调试区（规范 {@code docs/仪表导航控制说明.md}）。
     *
     * <p>旧版只发 {@code NAVIGATION_UPDATES}(②)、从不发 {@code 10001} 摘要(③)，
     * 按规范 §4.1「只发 ② 不发 ③，仪表不会更新」，等于从未真正可用。这里改为
     * 完整的 ①→②③→④ 序列，并按 §8 加状态机保护。
     */
    private void buildClusterNaviDebugSection() {
        section("仪表手动测试");
        TextView warning = Ui.text(host, "仅在车辆停稳时测试。这是向原车仪表投送导航，"
                + "与上面的副屏记录无关，也不需要连接盒子。\n"
                + "顺序必须是 ① 开始 → ②③ 发送 → ④ 结束；只发 ② 不发 ③ 仪表不会更新。\n"
                + "广播提交不代表仪表已接收，实际效果请观察仪表。", 13, Ui.TEXT_SECONDARY, false);
        warning.setPadding(dp(8), dp(12), dp(8), dp(12));
        list.addView(warning);

        clusterNaviStatus = Ui.text(host, "", 13, Ui.TEXT_SECONDARY, false);
        clusterNaviStatus.setTextIsSelectable(true);
        clusterNaviStatus.setPadding(dp(8), dp(4), dp(8), dp(4));
        clusterNaviStateLabel = Ui.text(host, "", 12, Ui.TEXT_SECONDARY, false);
        clusterNaviStateLabel.setPadding(dp(8), 0, dp(8), dp(8));
        LinearLayout statusCard = Ui.card(host);
        statusCard.addView(clusterNaviStatus);
        statusCard.addView(clusterNaviStateLabel);
        list.addView(statusCard);

        addRow(clusterNaviButton("原车组件自检", false, view -> checkClusterComponents()));
        addRow(clusterNaviButton("① 开始导航（EXTRA_STATE=8）", true, view -> {
            sendClusterMessages(host, clusterNavi.start(), "① 开始导航");
            refreshClusterNaviStatus();
        }));

        // 图标选择：47 项较多，用弹窗列表而不是下拉框
        LinearLayout iconRow = baseRow(true);
        iconRow.addView(title("② 转向图标"), grow());
        clusterNaviIconValue = Ui.text(host, "", 13, Ui.ACCENT, false);
        iconRow.addView(clusterNaviIconValue);
        iconRow.setOnClickListener(view -> showClusterIconPicker());
        addRow(iconRow);

        LinearLayout distanceRow = baseRow(false);
        distanceRow.addView(title("路口距离（米）"), grow());
        clusterNaviDistance = new EditText(host);
        clusterNaviDistance.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        clusterNaviDistance.setSingleLine(true);
        clusterNaviDistance.setText("300");
        clusterNaviDistance.setTextSize(14);
        clusterNaviDistance.setGravity(Gravity.END);
        clusterNaviDistance.setWidth(dp(96));
        clusterNaviDistance.setContentDescription("路口距离（米）");
        distanceRow.addView(clusterNaviDistance);
        addRow(distanceRow);

        addRow(clusterNaviButton("②③ 发送转向 + 摘要（触发提交）", true,
                view -> sendClusterTurnAndFlush()));
        addRow(clusterNaviButton("④ 结束导航（清除图标）", false,
                view -> {
                    sendClusterMessages(host, clusterNavi.stop(), "④ 结束导航");
                    refreshClusterNaviStatus();
                }));
        addRow(clusterNaviButton("退出导航（EXTRA_STATE=2，可选）", false, view -> {
            // 规范 §4.2 可选项：直接让原车退出导航
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("KEY_TYPE", ClusterNavi.KEY_TYPE_STATE);
            extras.put("EXTRA_STATE", 2);
            sendClusterMessages(host, Collections.singletonList(
                    new ClusterNavi.Message(ClusterNavi.ACT_STANDARD, extras)), "退出导航");
        }));

        refreshClusterNaviStatus();
    }

    private Button clusterNaviButton(String label, boolean primary,
            View.OnClickListener listener) {
        Button button = Ui.button(host, label, primary);
        button.setMinimumHeight(dp(48));
        button.setOnClickListener(listener);
        return button;
    }

    /** ②③ 必须成对发送（规范 §4.1），所以两者合成一个按钮。 */
    private void sendClusterTurnAndFlush() {
        int iconId = clusterNaviIconId;
        ClusterNaviIcons.Icon icon = ClusterNaviIcons.find(iconId);
        if (icon == null) {
            android.widget.Toast.makeText(host, "请先选择转向图标",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        int distance;
        try {
            distance = Integer.parseInt(clusterNaviDistance.getText().toString().trim());
        } catch (NumberFormatException exception) {
            setClusterNaviMessage("路口距离必须是整数（1~" + ClusterNavi.MAX_DISTANCE + " 米）",
                    Ui.WARNING);
            return;
        }
        if (!clusterNavi.isActive()) {
            setClusterNaviMessage("未开始导航，已忽略。请先点 ① 开始导航。", Ui.WARNING);
            return;
        }
        List<ClusterNavi.Message> messages;
        try {
            messages = ClusterNavi.combine(
                    clusterNavi.turn(iconId, distance),
                    clusterNavi.flush(distance, 0, "", 0, "", ""));
        } catch (IllegalArgumentException exception) {
            setClusterNaviMessage(exception.getMessage(), Ui.WARNING);
            return;
        }
        clusterNaviDistance.clearFocus();
        sendClusterMessages(host, messages,
                "②③ " + ClusterNaviIcons.describe(icon) + "，" + distance + " 米");
        refreshClusterNaviStatus();
    }

    private void showClusterIconPicker() {
        String[] labels = new String[ClusterNaviIcons.ALL.size()];
        int checked = 0;
        for (int index = 0; index < labels.length; index++) {
            ClusterNaviIcons.Icon icon = ClusterNaviIcons.ALL.get(index);
            labels[index] = ClusterNaviIcons.describe(icon);
            if (icon.id == clusterNaviIconId) {
                checked = index;
            }
        }
        new AlertDialog.Builder(host)
                .setTitle("选择转向图标（规范 §6）")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    clusterNaviIconId = ClusterNaviIcons.ALL.get(which).id;
                    dialog.dismiss();
                    refreshClusterNaviStatus();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 原车组件自检（规范 §4.5）。
     *
     * <p>只报告安装/启用状态。<b>「已安装」不等于「服务在运行」</b>——接收器是动态注册的，
     * 普通广播不会拉起这些服务。
     */
    private void checkClusterComponents() {
        String[] packages = {
                "com.chinatsp.dual.service",
                "com.chinatsp.tsetservices",
                "com.chinatsp.mcuservice2",
        };
        StringBuilder report = new StringBuilder("原车组件自检（仅安装/启用状态，"
                + "不代表服务在运行）：");
        for (String packageName : packages) {
            String state;
            int color;
            try {
                android.content.pm.ApplicationInfo info =
                        host.getPackageManager().getApplicationInfo(packageName, 0);
                if (info.enabled) {
                    state = "已安装并启用";
                    color = Ui.TEXT;
                } else {
                    state = "已安装但被禁用";
                    color = Ui.WARNING;
                }
            } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
                state = "未安装";
                color = Ui.ERROR;
            }
            report.append('\n').append(packageName).append('：').append(state);
            android.util.Log.i("AutoPlayClusterNavi", packageName + " → " + state);
        }
        report.append("\n\n若任一组件未安装/禁用，先诊断原车服务，不要改本应用的协议字段。");
        setClusterNaviMessage(report.toString(), Ui.TEXT_SECONDARY);
    }

    /**
     * 按 {@link ClusterNavi.Message} 组装并发送广播。
     *
     * <p>{@code putExtra} 必须传<b>基本类型 int</b>：传装箱的 {@code Integer} 会走
     * {@code Serializable} 重载，原车用 {@code getIntExtra} 读会拿到默认值，
     * 表现为「发出去了但没反应」（规范 §2.2）。
     */
    private void sendClusterMessages(android.content.Context context,
            List<ClusterNavi.Message> messages, String label) {
        if (messages.isEmpty()) {
            setClusterNaviMessage("没有可发送的消息（" + label + "）"
                    + "\n未开始导航时 ②③④ 都会被忽略，请先点 ①。", Ui.WARNING);
            return;
        }
        StringBuilder text = new StringBuilder();
        int sent = 0;
        for (ClusterNavi.Message message : messages) {
            Intent intent = new Intent(message.action);
            for (Map.Entry<String, Object> entry : message.extras.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Integer) {
                    // 关键：intValue() 让重载解析到 putExtra(String, int)
                    intent.putExtra(entry.getKey(), ((Integer) value).intValue());
                } else {
                    intent.putExtra(entry.getKey(), String.valueOf(value));
                }
            }
            try {
                context.sendBroadcast(intent);
                sent++;
                text.append("TX ").append(message).append('\n');
            } catch (SecurityException exception) {
                android.util.Log.e("AutoPlayClusterNavi", "广播被拒绝：" + message.action, exception);
                setClusterNaviMessage("发送失败（SecurityException）：" + exception
                        + "\n" + message.action, Ui.ERROR);
                return;
            }
        }
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date());
        String summary = time + " 已发送 " + sent + " 条：" + label
                + "\n" + text + "仅表示本应用调用了发送，不代表仪表已切换。";
        android.util.Log.i("AutoPlayClusterNavi", label + " → " + text);
        setClusterNaviMessage(summary, Ui.TEXT_SECONDARY);
    }

    private void setClusterNaviMessage(String message, int color) {
        if (clusterNaviStatus == null) {
            return;
        }
        clusterNaviStatus.setText(message);
        clusterNaviStatus.setTextColor(color);
    }

    /** 刷新图标/状态显示，让「未开始导航」这类前置条件一眼可见。 */
    private void refreshClusterNaviStatus() {
        if (clusterNaviIconValue != null) {
            ClusterNaviIcons.Icon icon = ClusterNaviIcons.find(clusterNaviIconId);
            clusterNaviIconValue.setText(icon == null
                    ? "未选择"
                    : icon.label + " · ID " + icon.id);
        }
        if (clusterNaviStateLabel != null) {
            clusterNaviStateLabel.setText(clusterNavi.isActive() ? "导航中（可发 ②③）" : "未开始");
            clusterNaviStateLabel.setTextColor(clusterNavi.isActive() ? Ui.ACCENT : Ui.TEXT_SECONDARY);
        }
        if (clusterNaviStatus != null && clusterNaviStatus.getText().length() == 0) {
            setClusterNaviMessage("尚未发送。顺序：① → ②③ → ④。", Ui.TEXT_SECONDARY);
        }
    }

    /** Activity 销毁时按规范 §1/§8.4 调用：幂等的 {@code stop()}。 */
    void releaseClusterNavi() {
        if (!clusterNavi.isActive()) {
            return;
        }
        List<ClusterNavi.Message> messages = clusterNavi.stop();
        for (ClusterNavi.Message message : messages) {
            Intent intent = new Intent(message.action);
            for (Map.Entry<String, Object> entry : message.extras.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Integer) {
                    intent.putExtra(entry.getKey(), ((Integer) value).intValue());
                } else {
                    intent.putExtra(entry.getKey(), String.valueOf(value));
                }
            }
            try {
                host.sendBroadcast(intent);
            } catch (SecurityException exception) {
                android.util.Log.e("AutoPlayClusterNavi", "退出时清除仪表失败", exception);
            }
        }
        android.util.Log.i("AutoPlayClusterNavi", "页面销毁，已发送仪表导航结束");
    }

    void updateNavigationLog() {
        if (navigationLog != null) navigationLog.setText(host.navigationLogText());
        if (dashboardStatus != null) dashboardStatus.setText(host.dashboardText());
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
        steeringLogCard = null;
        navigationLog = null;
        // 仪表调试的视图引用随页面重建失效，状态机本身（clusterNavi）要保留
        clusterNaviStatus = null;
        clusterNaviIconValue = null;
        clusterNaviStateLabel = null;
        clusterNaviDistance = null;
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
        boolean stacked = options.length > 3;
        if (stacked) {
            row.setOrientation(LinearLayout.VERTICAL);
            row.addView(title(title));
        } else {
            row.addView(title(title), grow());
        }

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
            segments[i].setPadding(dp(stacked ? 6 : 12), dp(7), dp(stacked ? 6 : 12), dp(7));
            segments[i].setOnClickListener(view -> {
                prefs().edit().putInt(key, index).apply();
                paintSegments(segments, index);
                if (onChange != null) {
                    onChange.run();
                }
            });
            if (stacked) control.addView(segments[i], new LinearLayout.LayoutParams(0, -2, 1));
            else control.addView(segments[i]);
        }
        paintSegments(segments, selected);
        if (stacked) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(8);
            row.addView(control, params);
        } else {
            row.addView(control);
        }
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

    /**
     * 分段控件，但把 {@code values[index]} 写入偏好而不是下标。
     *
     * <p>用于「夜间模式」这类语义值不能等于下标的配置：偏好沿用 2 / 3，
     * 只暴露「跟随手机」「大灯联动」两项。历史值 0 / 1 会被收敛到 2（跟随手机）。
     */
    private void segmentedValueRow(String title, String[] options, String key, int[] values,
            int defaultIndex, Runnable onChange) {
        if (options.length != values.length) {
            throw new IllegalArgumentException("options and values must match: " + title);
        }
        LinearLayout row = baseRow(false);
        boolean stacked = options.length > 3;
        if (stacked) {
            row.setOrientation(LinearLayout.VERTICAL);
            row.addView(title(title));
        } else {
            row.addView(title(title), grow());
        }

        LinearLayout control = new LinearLayout(host);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setPadding(dp(3), dp(3), dp(3), dp(3));
        control.setBackground(Ui.rounded(Ui.DIVIDER, dp(12)));
        int selected = selectedValueIndex(values, prefs().getInt(key, values[defaultIndex]),
                defaultIndex);
        TextView[] segments = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            segments[i] = Ui.text(host, options[i], 14, Ui.TEXT_SECONDARY, false);
            segments[i].setGravity(Gravity.CENTER);
            segments[i].setPadding(dp(stacked ? 6 : 12), dp(7), dp(stacked ? 6 : 12), dp(7));
            segments[i].setOnClickListener(view -> {
                prefs().edit().putInt(key, values[index]).apply();
                paintSegments(segments, index);
                if (onChange != null) {
                    onChange.run();
                }
            });
            if (stacked) control.addView(segments[i], new LinearLayout.LayoutParams(0, -2, 1));
            else control.addView(segments[i]);
        }
        paintSegments(segments, selected);
        if (stacked) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(8);
            row.addView(control, params);
        } else {
            row.addView(control);
        }
        addRow(row);
    }

    /**
     * 在 {@code values} 中定位当前值；历史遗留的越界值回退到默认项，
     * 避免下拉/分段控件因找不到下标而崩溃。
     */
    static int selectedValueIndex(int[] values, int current, int defaultIndex) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return i;
        }
        return Math.min(Math.max(defaultIndex, 0), values.length - 1);
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
        int selected = valueIndex(values, prefs().getInt(key, defaultValue), defaultValue);
        TextView value = Ui.text(host, choices[selected], 15, Ui.ACCENT, true);
        row.addView(value);
        row.addView(chevron());
        row.setOnClickListener(view -> new AlertDialog.Builder(host)
                .setTitle(title)
                .setSingleChoiceItems(choices,
                        valueIndex(values, prefs().getInt(key, defaultValue), defaultValue),
                        (dialog, which) -> {
                            prefs().edit().putInt(key, values[which]).apply();
                            value.setText(choices[which]);
                            dialog.dismiss();
                            changed.run();
                        }).setNegativeButton("取消", null).show());
        addRow(row);
    }

    /** 定位选项下标；未匹配时回退到默认值对应的位置，避免历史越界值导致崩溃。 */
    private static int valueIndex(int[] values, int selected, int defaultValue) {
        for (int i = 0; i < values.length; i++) if (values[i] == selected) return i;
        for (int i = 0; i < values.length; i++) if (values[i] == defaultValue) return i;
        return 0;
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
        steeringHint = Ui.text(host, "选择功能后按一下方向盘键：短按绑定单击，按住 0.8 秒绑定长按",
                15, Ui.TEXT, true);
        steeringHint.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.addView(steeringHint);
        TextView help = Ui.text(host,
                SteeringProtocol.vendorEnabled(prefs())
                        ? "长安 CS35Plus：支持 Android 按键和厂家 NEXT / PRE / VR / MAIN 广播。MAIN 为触摸按钮，按下即可学习；其他键按下并松开。\n\n语音键（VR）默认单击返回主页、长按唤醒 Siri，按住满 0.8 秒立即触发，无需松手。其他厂家键仅支持单击。\n\n同一 Android 按键可同时拥有单击与长按：短按一次学习单击，按住 0.8 秒学习长按。主页、返回控制手机投屏，不会阻止车机原有动作。"
                        : "默认协议：仅支持 Android 按键。\n\n同一按键可同时拥有单击与长按：短按一次学习单击，按住 0.8 秒学习长按。使用时按住满 0.8 秒立即触发长按，无需松手。两种协议的学习绑定独立保存。",
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
            steeringBindings[i].setMaxLines(3);
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
        toggleRow("记录按键日志", STEERING_LOG_PREFERENCE, false, value -> {
            if (!value) clearSteeringLog();
            updateSteeringLogCard();
        });
        LinearLayout logCard = Ui.card(host);
        steeringLogCard = logCard;
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
        updateSteeringLogCard();
        updateSteeringBindings();
        return page;
    }

    /** 按键日志默认关闭，关闭时隐藏整个日志卡片。 */
    private void updateSteeringLogCard() {
        if (steeringLogCard == null) return;
        boolean enabled = prefs().getBoolean(STEERING_LOG_PREFERENCE, false);
        steeringLogCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    void cancelSteeringLearning() {
        if (learningCommand >= 0) {
            android.util.Log.i("AutoPlayKeys", "learn_end button=" + selectedSteeringActionName(learningCommand));
        }
        learningCommand = -1;
        learningKey = KeyEvent.KEYCODE_UNKNOWN;
        learningDownTime = 0;
        learningVendor = null;
        learningVendorDownTime = 0;
        if (steeringHint != null) {
            steeringHint.setText("选择功能后按一下方向盘键：短按绑定单击，按住 0.8 秒绑定长按");
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
            learningDownTime = android.os.SystemClock.elapsedRealtime();
        } else if (event.getAction() == KeyEvent.ACTION_UP && code == learningKey) {
            if (event.isCanceled()) {
                cancelSteeringLearning();
                return true;
            }

            // 按住时长决定绑定到单击还是长按槽位。
            long duration = android.os.SystemClock.elapsedRealtime() - learningDownTime;
            boolean longPress = learningDownTime > 0 && duration >= SteeringProtocol.LONG_PRESS_MS;
            prefs().edit().putInt(
                    SteeringProtocol.androidBindingKey(prefs(), code, longPress),
                    learningCommand).apply();
            android.util.Log.i("AutoPlayKeys", "learn_bound button=" + selectedSteeringActionName(learningCommand)
                    + " key=" + KeyEvent.keyCodeToString(code) + " keyCode=" + code
                    + " longPress=" + longPress + " held=" + duration
                    + "ms scanCode=" + event.getScanCode());
            cancelSteeringLearning();
            updateSteeringBindings();
            steeringHint.setText((longPress ? "已绑定长按：" : "已绑定单击：")
                    + KeyEvent.keyCodeToString(code));
            steeringHint.setTextColor(Ui.ACCENT);
        }
        return true;
    }

    /**
     * 厂家广播的学习处理。
     *
     * <p>语音键（VR）按松手时长决定写入单击还是长按槽位；MAIN 只有按下事件，
     * 只能学习单击。返回 {@code true} 表示已消费该事件。
     */
    boolean handleVendorKey(String code, String state, boolean press, long now) {
        if (!SteeringProtocol.vendorEnabled(prefs())) return false;
        if (learningCommand < 0) return false;
        if (learningKey != KeyEvent.KEYCODE_UNKNOWN) return true;
        if (press && learningVendor == null) {
            learningVendor = code;
            learningVendorDownTime = now;
        }
        if (!code.equals(learningVendor)) return true;
        boolean bind;
        boolean longPress = false;
        if ("MAIN".equals(code)) {
            bind = press;
        } else if ("UP".equals(state)) {
            bind = true;
            longPress = VendorKeys.supportsLongPress(code)
                    && learningVendorDownTime > 0
                    && now - learningVendorDownTime >= SteeringProtocol.LONG_PRESS_MS;
        } else {
            bind = false;
        }
        if (!bind) return true;
        prefs().edit().putInt(SteeringProtocol.vendorBindingKey(prefs(), code, longPress),
                learningCommand).apply();
        android.util.Log.i("AutoPlayKeys", "learn_bound vendor=" + code
                + " action=" + learningCommand + " longPress=" + longPress);
        cancelSteeringLearning();
        updateSteeringBindings();
        steeringHint.setText((longPress ? "已绑定长按：厂家 " : "已绑定单击：厂家 ") + code);
        steeringHint.setTextColor(Ui.ACCENT);
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
        // 日志默认关闭；关闭时不缓存，避免无谓的内存占用。
        if (!prefs().getBoolean(STEERING_LOG_PREFERENCE, false)) return;
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
        SharedPreferences prefs = prefs();
        boolean vendor = SteeringProtocol.vendorEnabled(prefs);
        String androidPrefix = SteeringProtocol.androidPrefix(prefs);
        String vendorPrefix = SteeringProtocol.vendorPrefix();
        java.util.Map<String, ?> all = prefs.getAll();
        for (int i = 0; i < STEERING_COMMANDS.length; i++) {
            StringBuilder single = new StringBuilder();
            StringBuilder held = new StringBuilder();
            Integer command = STEERING_COMMANDS[i];
            for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                if (!command.equals(entry.getValue())) {
                    continue;
                }
                String key = entry.getKey();
                if (vendor && key.startsWith(vendorPrefix)) {
                    String suffix = key.substring(vendorPrefix.length());
                    boolean vendorLong = suffix.startsWith(SteeringProtocol.LONG_SUFFIX);
                    if (vendorLong) {
                        suffix = suffix.substring(SteeringProtocol.LONG_SUFFIX.length());
                    }
                    appendBinding(vendorLong ? held : single, "厂家 " + suffix);
                    continue;
                }
                AndroidBinding binding = parseAndroidBinding(key, androidPrefix);
                if (binding == null) {
                    // 历史遗留或外部写入的脏键，跳过以免崩溃。
                    android.util.Log.w("AutoPlayKeys", "skip_unknown_binding key=" + key);
                    continue;
                }
                appendBinding(binding.longPress ? held : single,
                        KeyEvent.keyCodeToString(binding.keyCode));
            }
            // 语音键默认值（单击主页 / 长按 Siri）未写入偏好，这里显式提示方便用户理解
            if (vendor && single.length() == 0
                    && command == SteeringProtocol.DEFAULT_VR_SINGLE) {
                appendBinding(single, "厂家 " + SteeringProtocol.VENDOR_VR + "（默认）");
            }
            if (vendor && held.length() == 0
                    && command == SteeringProtocol.DEFAULT_VR_LONG) {
                appendBinding(held, "厂家 " + SteeringProtocol.VENDOR_VR + "（默认）");
            }
            if (steeringBindings[i] != null) {
                steeringBindings[i].setText(describeBindings(single, held));
            }
        }
    }

    private static void appendBinding(StringBuilder builder, String value) {
        if (builder.length() > 0) builder.append("、");
        builder.append(value);
    }

    /** 单击/长按分别成行展示，都为空时提示「点击学习」。 */
    private static String describeBindings(StringBuilder single, StringBuilder held) {
        if (single.length() == 0 && held.length() == 0) return "点击学习";
        StringBuilder text = new StringBuilder();
        if (single.length() > 0) text.append("单击：").append(single);
        if (held.length() > 0) {
            if (text.length() > 0) text.append('\n');
            text.append("长按：").append(held);
        }
        return text.toString();
    }

    /** Android 按键绑定的解析结果：键码 + 是否长按槽位。 */
    static final class AndroidBinding {
        final int keyCode;
        final boolean longPress;

        AndroidBinding(int keyCode, boolean longPress) {
            this.keyCode = keyCode;
            this.longPress = longPress;
        }
    }

    /**
     * 从 {@code <prefix>[long_]<keyCode>} 形式的偏好键中解析绑定。
     *
     * <p>偏好数据的来源不受本应用完全控制（历史版本或外部写入都可能留下非数字后缀），
     * 因此解析失败时返回 {@code null} 而不是抛出异常。
     */
    static AndroidBinding parseAndroidBinding(String key, String prefix) {
        if (key == null || prefix == null || !key.startsWith(prefix)) {
            return null;
        }
        String suffix = key.substring(prefix.length());
        boolean longPress = suffix.startsWith(SteeringProtocol.LONG_SUFFIX);
        if (longPress) {
            suffix = suffix.substring(SteeringProtocol.LONG_SUFFIX.length());
        }
        if (suffix.isEmpty()) {
            return null;
        }
        for (int index = 0; index < suffix.length(); index++) {
            char value = suffix.charAt(index);
            // 只接受可选负号加纯数字，避免 NumberFormatException 拖垮界面。
            if (index == 0 && value == '-' && suffix.length() > 1) {
                continue;
            }
            if (value < '0' || value > '9') {
                return null;
            }
        }
        try {
            return new AndroidBinding(Integer.parseInt(suffix), longPress);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 仅取键码的兼容版本；长按绑定同样返回其键码。 */
    static Integer parseAndroidKeyCode(String key, String prefix) {
        AndroidBinding binding = parseAndroidBinding(key, prefix);
        return binding == null ? null : binding.keyCode;
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
        row.addView(title("OEM 设置"), grow());
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
