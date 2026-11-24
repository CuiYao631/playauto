package cn.autoplay.replacement;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 701;
    private static final int REQUEST_ENV_MICROPHONE = 702;
    private static final int REQUEST_BOOT_NOTIFICATIONS = 703;
    private static final int REQUEST_CONNECTION_NOTIFICATIONS = 704;
    private static final int REQUEST_INSTALL_PACKAGE = 705;
    private static final int PAGE_SETTINGS = 0;
    private static final int PAGE_MORE = 1;
    private static final int PAGE_ENVIRONMENT = 2;
    private static final int PAGE_STEERING = 3;
    private static final int PAGE_ADVANCED = 4;
    private static final int PAGE_NAVIGATION = 5;
    private static boolean advancedUnlocked;
    private int versionTapCount;
    private long lastVersionTapMs;
    static final String VENDOR_KEY_ACTION = "com.coagent.intent.action.KEY_CHANGED";
    private boolean vendorKeysRegistered;
    private final VendorKeys vendorKeys = new VendorKeys();
    private final android.content.BroadcastReceiver vendorKeyReceiver =
            new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!activityVisible || !VENDOR_KEY_ACTION.equals(intent.getAction())) return;
            if (!SteeringProtocol.vendorEnabled(getPreferences(MODE_PRIVATE))) return;
            boolean ordered = isOrderedBroadcast();
            boolean noAbort = (intent.getFlags() & Intent.FLAG_RECEIVER_NO_ABORT) != 0;
            android.util.Log.i("AutoPlayKeys", "vendor_broadcast ordered=" + ordered
                    + " noAbort=" + noAbort + " intercepted=false");
            boolean learningPage = settingsVisible && settingsPage == PAGE_STEERING;
            Bundle extras = intent.getExtras();
            Object code = extras == null ? null : extras.get("Key_code");
            Object state = extras == null ? null : extras.get("Key_state");
            if (!(code instanceof String) || !(state instanceof String)) {
                android.util.Log.w("AutoPlayKeys", "vendor_broadcast invalid Key_code/Key_state");
                if (learningPage) screens.recordVendorKey("无效字段",
                        "Key_code / Key_state 必须为字符串", ordered, noAbort);
                return;
            }
            String key = (String) code;
            String phase = (String) state;
            if (learningPage) screens.recordVendorKey(key, phase, ordered, noAbort);
            if (!VendorKeys.supported(key) || !VendorKeys.stateSupported(phase)) {
                android.util.Log.w("AutoPlayKeys", "Unsupported vendor key or state; ignored");
                return;
            }
            android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
            long now = android.os.SystemClock.elapsedRealtime();
            boolean press = vendorKeys.press(key, phase, now);
            if (learningPage && screens.handleVendorKey(key, phase, press, now)) return;

            int single = SteeringProtocol.vendorCommand(prefs, key, false);
            int held = SteeringProtocol.vendorCommand(prefs, key, true);
            if (VendorKeys.supportsLongPress(key) && held >= 0) {
                // 语音键：DOWN 不立即执行，满 800ms 自动触发长按；UP 时未触发过长按才走单击
                if (press) {
                    uiHandler.removeCallbacks(vendorLongPressTask);
                    vendorPress.begin(key, single, held, now);
                    uiHandler.postDelayed(vendorLongPressTask, SteeringProtocol.LONG_PRESS_MS);
                } else if ("UP".equals(phase)) {
                    uiHandler.removeCallbacks(vendorLongPressTask);
                    if (vendorPress.isPressed(key)) {
                        boolean longFired = vendorPress.isLongFired();
                        long heldMs = vendorPress.heldMs(now);
                        int action = vendorPress.finish(now);
                        android.util.Log.i("AutoPlayKeys", "vendor_dispatch key=" + key
                                + " held=" + heldMs + "ms longFired=" + longFired
                                + " action=" + action);
                        if (action >= 0 && phonePlugged && connectionService != null) {
                            connectionService.sendSteeringCommand(action, "vendor");
                        }
                    }
                }
                return;
            }
            if (press && single >= 0 && phonePlugged && connectionService != null) {
                connectionService.sendSteeringCommand(single, "vendor");
            }
        }
    };

    private UsbManager usbManager;
    private LinearLayout rootPage;
    private Ui.StatusText connectionStatus;
    private Ui.StatusText phoneStatus;
    private Widgets.CarPlayLogo stageLogo;
    private Widgets.Spinner stageSpinner;
    private TextView stagePrompt;
    private LinearLayout stageCenter;
    private LinearLayout stagePromptCard;
    private View stagePromptDot;
    private SettingsScreens screens;
    private int settingsPage;
    private boolean phonePlugged;
    private boolean bluetoothLinked;
    private boolean wifiLinked;
    private String boxVersion;
    private int lastVideoWidth;
    private int lastVideoHeight;
    private boolean themeRefreshPending;
    private List<CarLogoOption> carLogoOptions;
    private CarLogoOption selectedCarLogo;
    private boolean settingsVisible;
    private int insetTop;
    private int insetBottom;
    private int insetLeft;
    private int insetRight;
    private boolean touchEnabled = true;
    private BoxProtocolSession protocolSession;
    private H264SurfaceRenderer videoRenderer;
    private boolean videoStarted;
    private boolean projectionMode;
    private android.media.ToneGenerator testTone;
    private ConnectionService connectionService;
    private boolean serviceBound;
    private boolean activityVisible;
    private boolean hostPageRequested;
    private boolean requestingMicrophone;
    /** 最近一次视频解码失败原因；非空时首页给出可操作的提示而不是「连接失败」。 */
    private String videoError;
    /** 已绑定长按的按键状态机：按住满 800ms 立即触发长按，无需等松手。 */
    private final SteeringProtocol.PressState steeringPress = new SteeringProtocol.PressState();
    /** 厂家语音键的长按状态机，与 Android 按键互不干扰。 */
    private final SteeringProtocol.PressState vendorPress = new SteeringProtocol.PressState();
    /** 按住满 800ms 立即触发长按。 */
    private final Runnable steeringLongPressTask = () -> {
        if (!steeringPress.isPressed()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        int action = steeringPress.longActionIfDue(now);
        if (action < 0) {
            return;
        }
        android.util.Log.i("AutoPlayKeys", "long_press_fired action=" + action
                + " held=" + steeringPress.heldMs(now) + "ms");
        if (connectionService != null) {
            connectionService.sendSteeringCommand(action, "android");
        }
    };
    /** 厂家语音键：按住满 800ms 立即触发长按（唤醒 Siri）。 */
    private final Runnable vendorLongPressTask = () -> {
        if (!vendorPress.isPressed()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        int action = vendorPress.longActionIfDue(now);
        if (action < 0) {
            return;
        }
        android.util.Log.i("AutoPlayKeys", "vendor_long_press_fired key=" + vendorPress.pressedKey()
                + " action=" + action + " held=" + vendorPress.heldMs(now) + "ms");
        if (phonePlugged && connectionService != null) {
            connectionService.sendSteeringCommand(action, "vendor");
        }
    };

    // ---- 升级页面状态（盒子固件 / 软件更新共用同一套页面样式） ----
    private static final int FIRMWARE_IDLE = 0;
    private static final int FIRMWARE_UPGRADING = 1;
    private static final int FIRMWARE_WAIT_REBOOT = 2;
    private static final int FIRMWARE_FAILED = 3;
    private static final long FIRMWARE_REBOOT_TIMEOUT_MS = 120_000L;
    private boolean firmwarePageVisible;
    private int firmwarePagePhase = FIRMWARE_IDLE;
    private boolean firmwareSawDisconnect;
    private boolean firmwareSuppressed;
    private int firmwareShownProgress = -1;
    private UpgradeProgressPage firmwarePage;
    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable firmwareRebootTimeout = () -> {
        if (firmwarePagePhase == FIRMWARE_WAIT_REBOOT) {
            finishFirmwarePage("未检测到盒子重启，请手动确认固件版本");
        }
    };

    // ---- 软件更新页面状态 ----
    private static final int APP_UPDATE_IDLE = 0;
    private static final int APP_UPDATE_DOWNLOADING = 1;
    private static final int APP_UPDATE_READY = 2;
    private static final int APP_UPDATE_INSTALLING = 3;
    private static final int APP_UPDATE_FAILED = 4;
    private boolean appUpdatePageVisible;
    private int appUpdatePhase = APP_UPDATE_IDLE;
    private UpgradeProgressPage appUpdatePage;
    private String appUpdateFailure = "";
    private final ConnectionService.Client connectionClient = new ConnectionService.Client() {
        @Override public void onNavigationLogChanged() {
            screens.updateNavigationLog();
        }
        @Override public void onHostUiRequested() {
            hostPageRequested = true;
            setProjectionMode(false);
            videoRenderer.setVisibility(View.GONE);
            showMain();
            updateStage();
        }
        @Override public void onConnectionState() {
            screens.updateBoxNames();
            screens.updateNavigationLog();
            if (screens != null) screens.updateAdvancedStatus();
            ConnectionService service = connectionService;
            if (service == null) return;
            protocolSession = service.getSession();
            phonePlugged = service.phonePlugged;
            bluetoothLinked = service.bluetoothLinked;
            wifiLinked = service.wifiLinked;
            boxVersion = service.boxVersion;
            connectionStatus.setText(service.boxStatus);
            phoneStatus.setText(service.phoneStatus);
            syncFirmwareState(service);
            if (!phonePlugged) {
                hostPageRequested = false;
                videoStarted = false;
                videoError = null;
                lastVideoWidth = lastVideoHeight = 0;
                videoRenderer.setVisibility(View.GONE);
                setProjectionMode(false);
            }
            updateStage();
        }

        @Override public void onVideoFrame(VideoFrame frame) {
            boolean sizeChanged = frame.getWidth() != lastVideoWidth || frame.getHeight() != lastVideoHeight;
            lastVideoWidth = frame.getWidth();
            lastVideoHeight = frame.getHeight();
            if (videoStarted && sizeChanged) layoutVideo();
            if (!videoStarted) {
                videoStarted = true;
                connectionStatus.setText("盒子：已收到 CarPlay 视频帧，正在解码");
                if (!hostPageRequested) setProjectionMode(true);
            }
            videoRenderer.setVisibility(hostPageRequested ? View.GONE : View.VISIBLE);
            videoRenderer.submitFrame(frame);
        }

        @Override public void onMicrophonePermissionNeeded() {
            if (Build.VERSION.SDK_INT >= 23 && !requestingMicrophone && activityVisible) {
                requestingMicrophone = true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            }
        }
    };
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            connectionService = ((ConnectionService.LocalBinder) binder).getService();
            int[] size = getRequestedVideoSize();
            connectionService.attach(connectionClient, size[0], size[1]);
            connectionService.setClientVisible(activityVisible);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            connectionService = null;
            protocolSession = null;
            phonePlugged = bluetoothLinked = wifiLinked = false;
            videoStarted = false;
            hostPageRequested = false;
            videoRenderer.setVisibility(View.GONE);
            setProjectionMode(false);
            connectionStatus.setText("盒子：连接服务已中断，请重新打开应用");
            phoneStatus.setText("手机：未连接");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyTheme();
        VideoPreferences.migrate(getPreferences(MODE_PRIVATE));
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        applyKeepScreenOn(getPreferences(MODE_PRIVATE).getBoolean("keep_screen_on", true));
        touchEnabled = getPreferences(MODE_PRIVATE).getBoolean("touch_enabled", true);
        carLogoOptions = loadCarLogoOptions();
        selectedCarLogo = findCarLogo(getPreferences(MODE_PRIVATE)
            .getString("CarPlayOEMIconPath", CarLogoUpload.DEFAULT_PATH));
        if (selectedCarLogo == null && !carLogoOptions.isEmpty()) {
            selectedCarLogo = carLogoOptions.get(0);
        }
        connectionStatus = new Ui.StatusText(this, Ui::classifyBox);
        phoneStatus = new Ui.StatusText(this, Ui::classifyPhone);
        connectionStatus.setText("盒子：未连接");
        phoneStatus.setText("手机：未连接");
        screens = new SettingsScreens(this);
        connectionStatus.setOnChange(this::updateStage);
        phoneStatus.setOnChange(this::updateStage);
        createVideoRenderer();
        buildScreen();
        setContentView(rootPage);
        serviceBound = bindService(new Intent(this, ConnectionService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
        if (!serviceBound) {
            connectionStatus.setText("盒子：无法绑定连接服务");
            android.util.Log.e("AutoPlay", "Cannot bind connection service");
        }
        onNavigationBarChanged();
        cancelBootNotification();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;
        if (screens != null) screens.updateOverlayPermissionStatus();
        if (screens != null) screens.updateAdvancedStatus();
        android.content.IntentFilter vendorFilter = new android.content.IntentFilter(VENDOR_KEY_ACTION);
        AndroidCompat.registerReceiver(this, vendorKeyReceiver, vendorFilter, true);
        vendorKeysRegistered = true;
        if (connectionService != null) connectionService.setClientVisible(true);
    }



    @Override
    protected void onPause() {
        activityVisible = false;
        vendorKeys.reset();
        uiHandler.removeCallbacks(steeringLongPressTask);
        uiHandler.removeCallbacks(vendorLongPressTask);
        steeringPress.reset();
        vendorPress.reset();
        if (vendorKeysRegistered) {
            unregisterReceiver(vendorKeyReceiver);
            vendorKeysRegistered = false;
        }
        if (screens != null) screens.cancelSteeringLearning();
        if (connectionService != null) connectionService.setClientVisible(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(steeringLongPressTask);
        uiHandler.removeCallbacks(vendorLongPressTask);
        steeringPress.reset();
        vendorPress.reset();
        if (testTone != null) {
            testTone.release();
            testTone = null;
        }
        if (connectionService != null) {
            connectionService.detach(connectionClient);
            connectionService = null;
        }
        protocolSession = null;
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        if (videoRenderer != null) {
            videoRenderer.close();
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        cancelBootNotification();
        setIntent(intent);
        if (connectionService != null) connectionService.connectAttached();
    }

    @Override
    public void onBackPressed() {
        if (firmwarePageVisible) {
            if (firmwarePagePhase == FIRMWARE_UPGRADING) {
                Toast.makeText(this, "升级进行中，请勿退出", Toast.LENGTH_SHORT).show();
                return;
            }
            exitFirmwarePage();
            return;
        }
        if (appUpdatePageVisible) {
            if (appUpdatePhase == APP_UPDATE_DOWNLOADING) {
                Toast.makeText(this, "正在下载更新，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            exitAppUpdatePage();
            return;
        }
        if (settingsVisible) {
            if (settingsPage == PAGE_SETTINGS) {
                showMain();
            } else if (settingsPage == PAGE_ADVANCED) {
                showMore();
            } else {
                showSettings();
            }
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CONNECTION_NOTIFICATIONS) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知未授权，后台服务仍可运行，但通知栏可能不显示连接提示",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQUEST_BOOT_NOTIFICATIONS) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知权限未授予；若系统禁止开机弹出界面，将无法显示启动通知",
                        Toast.LENGTH_LONG).show();
            }
            showBootAutoStartHelp();
            return;
        }
        if (requestCode == REQUEST_ENV_MICROPHONE) {
            if (settingsVisible && settingsPage == PAGE_ENVIRONMENT) {
                showSettingsPageOf(PAGE_ENVIRONMENT);
            }
            return;
        }
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        requestingMicrophone = false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (connectionService != null) {
                connectionService.updateBackgroundPolicy();
                connectionService.retryMicrophone();
            }
        } else {
            phoneStatus.setText("手机：麦克风权限未授予");
        }
    }

    private void createVideoRenderer() {
        videoRenderer = new H264SurfaceRenderer(this, new H264SurfaceRenderer.Listener() {
            @Override
            public void onDecoderError(String error) {
                runOnUiThread(() -> {
                    videoError = error;
                    connectionStatus.setText("盒子：H.264 解码失败：" + error);
                    ConnectionService service = connectionService;
                    if (service != null) service.recordDiagnostic("视频解码失败：" + error);
                    updateStage();
                });
            }

            @Override
            public void onTouchData(byte[] touchData) {
                BoxProtocolSession currentSession = protocolSession;
                if (touchEnabled && currentSession != null) {
                    currentSession.sendTouchFrame(touchData);
                }
            }

            @Override
            public void onKeyFrameNeeded() {
                BoxProtocolSession currentSession = protocolSession;
                if (currentSession != null) {
                    currentSession.requestKeyFrame();
                }
            }
        });
        videoRenderer.setVisibility(View.GONE);
        videoRenderer.setDecoderMode(getPreferences(MODE_PRIVATE).getInt("decoder_mode", VideoOptions.HARDWARE));
        videoRenderer.setFrameRate(VideoPreferences.frameRate(getPreferences(MODE_PRIVATE)));
    }

    private void buildScreen() {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        boolean compactLandscape = landscape && getResources().getConfiguration().screenHeightDp < 480;
        LinearLayout page = new LinearLayout(this);
        rootPage = page;
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.pageBackground());
        if (Build.VERSION.SDK_INT >= 21) page.setOnApplyWindowInsetsListener((view, insets) -> {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
            insetLeft = insets.getSystemWindowInsetLeft();
            insetRight = insets.getSystemWindowInsetRight();
            updateRootPadding();
            layoutVideo();
            return insets;
        });
        updateRootPadding();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);

        LinearLayout settingsPill = new LinearLayout(this);
        settingsPill.setOrientation(LinearLayout.HORIZONTAL);
        settingsPill.setGravity(android.view.Gravity.CENTER);
        settingsPill.setPadding(dp(10), dp(10), dp(10), dp(10));
        settingsPill.setMinimumHeight(dp(48));
        settingsPill.setContentDescription("设置");
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            settingsPill.setTooltipText("设置");
        }
        settingsPill.setBackground(Ui.ripple(
                Ui.ACCENT_SOFT, null,
                Ui.oval(android.graphics.Color.WHITE, dp(48))));
        settingsPill.addView(new Widgets.LinkIcon(this, Widgets.LinkIcon.GEAR),
                new LinearLayout.LayoutParams(dp(28), dp(28)));
        settingsPill.setOnClickListener(view -> showSettings());
        header.addView(settingsPill, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);

        stageCenter = new LinearLayout(this);
        stageCenter.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        stageCenter.setGravity(landscape
                ? android.view.Gravity.CENTER : android.view.Gravity.CENTER_HORIZONTAL);
        stageCenter.setClipChildren(false);
        stageCenter.setClipToPadding(false);
        stageLogo = new Widgets.CarPlayLogo(this);
        stageLogo.setContentDescription("CarPlay，点击返回投屏");
        stageLogo.setOnClickListener(view -> {
            if (!hostPageRequested || !phonePlugged) return;
            hostPageRequested = false;
            videoStarted = false;
            if (protocolSession != null) protocolSession.requestKeyFrame();
        });
        int logoSize = dp(landscape ? (compactLandscape ? 104 : 140) : 132);
        stageCenter.addView(stageLogo, new LinearLayout.LayoutParams(logoSize, logoSize));
        LinearLayout stageText = new LinearLayout(this);
        stageText.setOrientation(LinearLayout.VERTICAL);
        stageText.setGravity(landscape
                ? android.view.Gravity.CENTER_VERTICAL : android.view.Gravity.CENTER_HORIZONTAL);
        if (landscape) {
            stageText.setPadding(dp(18), 0, 0, 0);
        }
        int stageTextWidth = landscape
                ? LinearLayout.LayoutParams.WRAP_CONTENT : LinearLayout.LayoutParams.MATCH_PARENT;
        TextView appTitle = Ui.text(this, "CarPlay", landscape ? 34 : 26, Ui.TEXT, true);
        appTitle.setGravity(landscape ? android.view.Gravity.START : android.view.Gravity.CENTER);
        appTitle.setPadding(0, landscape ? 0 : dp(28), 0, 0);
        stageText.addView(appTitle, new LinearLayout.LayoutParams(
                stageTextWidth, LinearLayout.LayoutParams.WRAP_CONTENT));
        stageCenter.addView(stageText);

        stagePromptCard = new LinearLayout(this);
        stagePromptCard.setOrientation(LinearLayout.HORIZONTAL);
        stagePromptCard.setGravity(android.view.Gravity.CENTER_VERTICAL);
        stagePromptCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        stagePromptDot = new View(this);
        stagePromptDot.setBackground(Ui.oval(Ui.IDLE, dp(6)));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotParams.rightMargin = dp(8);
        stagePromptCard.addView(stagePromptDot, dotParams);
        stagePrompt = Ui.text(this, "", 12, Ui.TEXT_SECONDARY, false);
        stagePrompt.setGravity(android.view.Gravity.START);
        stagePrompt.setMaxLines(2);
        stagePrompt.setMaxWidth(dp(Math.max(80,
                getResources().getConfiguration().screenWidthDp - 132)));
        stagePrompt.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stagePromptCard.addView(stagePrompt);
        stagePromptCard.setContentDescription("连接状态，点击查看连接详情");
        stagePromptCard.setOnClickListener(view -> showConnectionDetails());
        stageSpinner = new Widgets.Spinner(this);
        stageSpinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        spinnerParams.leftMargin = dp(10);
        stagePromptCard.addView(stageSpinner, spinnerParams);
        stage.addView(stageCenter, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
        header.addView(stagePromptCard, 0, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        stage.addView(header, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP));
        page.addView(stage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        if (videoRenderer.getParent() instanceof ViewGroup) {
            ((ViewGroup) videoRenderer.getParent()).removeView(videoRenderer);
        }
        videoRenderer.setVisibility(View.GONE);
        page.addView(videoRenderer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0));
        page.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            page.post(this::updateRootPadding);
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                layoutVideo();
            }
        });
        updateStage();
    }

    private LinearLayout.LayoutParams rowParams(int topDp, int bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        params.bottomMargin = dp(bottomDp);
        return params;
    }

    private boolean isFullscreenApp() {
        return getPreferences(MODE_PRIVATE).getInt("fullscreen", 0) == 0;
    }

    private void updateRootPadding() {
        if (rootPage == null) {
            return;
        }
        View decor = getWindow().getDecorView();
        int[] rootLocation = new int[2];
        int[] decorLocation = new int[2];
        rootPage.getLocationInWindow(rootLocation);
        decor.getLocationInWindow(decorLocation);
        int left = rootLocation[0] - decorLocation[0];
        int top = rootLocation[1] - decorLocation[1];
        // The window may already exclude system bars after leaving immersive mode.
        int safeLeft = Math.max(0, insetLeft - left);
        int safeTop = Math.max(0, insetTop - top);
        int safeRight = Math.max(0, insetRight
                - (decor.getWidth() - left - rootPage.getWidth()));
        int safeBottom = Math.max(0, insetBottom
                - (decor.getHeight() - top - rootPage.getHeight()));
        if (projectionMode) {
            if (isFullscreenApp()) {
                rootPage.setPadding(0, 0, 0, 0);
            } else {
                rootPage.setPadding(safeLeft, safeTop, safeRight, safeBottom);
            }
        } else {
            rootPage.setPadding(safeLeft + dp(16), safeTop + dp(8),
                    safeRight + dp(16), safeBottom + dp(16));
        }
    }

    private void updateStage() {
        if (stageLogo == null || connectionStatus == null || phoneStatus == null) {
            return;
        }
        int box = connectionStatus.getState();
        int phone = phoneStatus.getState();
        String phoneMessage = phoneStatus.getText().toString();
        String prompt;
        int dotColor = Ui.IDLE;
        boolean connecting = false;
        if (hostPageRequested && phonePlugged) {
            prompt = "手机已连接，点击 Logo 返回投屏";
            dotColor = Ui.ACCENT;
        } else if (box == Ui.STATE_ERROR) {
            prompt = "设备连接失败，请重新插拔设备";
            dotColor = Ui.ERROR;
        } else if (box == Ui.STATE_IDLE) {
            prompt = "请将设备插入车机的USB口";
        } else if (box == Ui.STATE_WAIT) {
            prompt = "连接中，请稍等...";
            dotColor = Ui.WARNING;
            connecting = true;
        } else if (videoError != null) {
            // 链路正常但画面解不出来：提示改解码方式，拔插盒子没用。
            prompt = "画面解码失败（" + videoError + "），请在设置 → 解码方式改用软解";
            dotColor = Ui.WARNING;
        } else if (phone == Ui.STATE_ERROR) {
            prompt = "手机连接失败，请检查蓝牙与Wi-Fi后重试";
            dotColor = Ui.ERROR;
        } else if (phonePlugged) {
            prompt = "连接中，请稍等...";
            connecting = true;
            dotColor = Ui.ACCENT;
        } else if (phoneMessage.contains("没有已配对手机")) {
            prompt = "正在查找，请打开手机蓝牙与Wi-Fi";
            dotColor = Ui.WARNING;
        } else if ((bluetoothLinked || wifiLinked) && phone == Ui.STATE_WAIT) {
            prompt = "连接中，请稍等...";
            connecting = true;
            dotColor = Ui.ACCENT;
        } else {
            prompt = "正在查找，请打开手机蓝牙与Wi-Fi";
            dotColor = Ui.ACCENT;
        }
        stageLogo.setActive(box != Ui.STATE_IDLE && box != Ui.STATE_ERROR && phone != Ui.STATE_ERROR);
        stageCenter.setVisibility(View.VISIBLE);
        stageSpinner.setVisibility(connecting ? View.VISIBLE : View.GONE);
        stagePromptCard.setVisibility(View.VISIBLE);
        stagePrompt.setText(prompt);
        ((android.graphics.drawable.GradientDrawable) stagePromptDot.getBackground()).setColor(dotColor);
        if (screens != null) screens.updateConnectionStatus(bluetoothLinked, wifiLinked);
        updateBackgroundControl();
    }

    void showConnectionDetails() {
        ConnectionService service = connectionService;
        StringBuilder message = new StringBuilder()
                .append("盒子：").append(connectionStateLabel(connectionStatus.getState()))
                .append('\n').append(connectionStatus.getText())
                .append("\n\n手机：").append(connectionStateLabel(phoneStatus.getState()))
                .append('\n').append(phoneStatus.getText());
        if (service != null) message.append("\n\n").append(service.usbDiagnostics());
        new AlertDialog.Builder(this)
                .setTitle("连接详情")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 供「高级功能 → USB 权限状态」直接显示，连接服务尚未就绪时给出说明。 */
    String connectionDiagnostics() {
        ConnectionService service = connectionService;
        return service == null ? "连接服务未就绪，请返回首页稍候" : service.usbDiagnostics();
    }

    private static String connectionStateLabel(int state) {
        switch (state) {
            case Ui.STATE_OK:
                return "已连接";
            case Ui.STATE_WAIT:
                return "连接中";
            case Ui.STATE_ERROR:
                return "连接失败";
            default:
                return "未连接";
        }
    }

    void showRootPermissionRequest() {
        new AlertDialog.Builder(this)
                .setTitle("申请 Root 权限")
                .setMessage("立即执行 su -c id，向已有的 root 管理器真实申请权限，无需插入 USB 盒子。"
                        + "\n\n车机必须已经 root；本功能不会自动 root，也不修改系统或 USB 权限。"
                        + "请在 root 管理器中允许请求，最多等待 30 秒。"
                        + "\n\n只有命令成功且返回 UID 0 才确认获得 Root。管理器已记住选择时可能不再弹窗。")
                .setNegativeButton("取消", null)
                .setPositiveButton("立即申请", (dialog, which) -> {
                    AlertDialog progress = new AlertDialog.Builder(this)
                            .setTitle("正在申请 Root 权限")
                            .setMessage("请在 root 管理器中允许请求，最多等待 30 秒。")
                            .setPositiveButton("知道了", (finished, button) -> finished.dismiss())
                            .setCancelable(false).show();
                    progress.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    RootUsbAuthorization.requestRoot(result -> {
                        android.util.Log.i("AutoPlayRootUsb", result);
                        screens.updateAdvancedStatus();
                        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) {
                            if (progress.isShowing()) progress.dismiss();
                            return;
                        }
                        progress.setTitle("Root 权限申请结果");
                        progress.setMessage(result + "\n\n本次仅检查 Root 身份，不修改 USB 权限或系统文件。");
                        progress.setCancelable(true);
                        progress.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    });
                    screens.updateAdvancedStatus();
                }).show();
    }

    void updateConnectionStatusViews() {
        screens.updateConnectionStatus(bluetoothLinked, wifiLinked);
    }

    private int themeMode() {
        return getPreferences(MODE_PRIVATE).getInt("theme_mode", 2);
    }

    private boolean isDarkTheme() {
        int mode = themeMode();
        if (mode == 1) {
            return false;
        }
        if (mode == 2) {
            return true;
        }
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyTheme() {
        boolean dark = isDarkTheme();
        Ui.applyTheme(dark);
        int themeId = getResources().getIdentifier(dark ? "AppThemeDark" : "AppTheme", "style", getPackageName());
        if (themeId != 0) {
            setTheme(themeId);
        }
        getWindow().setBackgroundDrawable(Ui.pageBackground());
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Ui.BG_TOP);
            getWindow().setNavigationBarColor(Ui.BG_BOTTOM);
        }
        if (Build.VERSION.SDK_INT < 23) return;
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(dark
                ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                : flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    void refreshTheme() {
        applyTheme();
        buildScreen();
        if (settingsVisible) {
            showSettingsPageOf(settingsPage);
        } else {
            setContentView(rootPage);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int[] size = getRequestedVideoSize();
        if (connectionService != null) connectionService.updateVideoSettings(size[0], size[1]);
        if (projectionMode) {
            themeRefreshPending = true;
            rootPage.post(this::layoutVideo);
            if (protocolSession != null) protocolSession.requestKeyFrame();
        } else {
            refreshTheme();
        }
    }

    void showMain() {
        screens.cancelSteeringLearning();
        settingsVisible = false;
        setContentView(rootPage);
        fadeIn(rootPage);
    }

    void showSettings() {
        showSettingsPageOf(PAGE_SETTINGS);
    }

    void showMore() {
        showSettingsPageOf(PAGE_MORE);
    }

    void showAdvanced() {
        showSettingsPageOf(PAGE_ADVANCED);
    }

    boolean advancedFeaturesUnlocked() {
        return advancedUnlocked;
    }

    void onVersionTapped() {
        if (advancedUnlocked) {
            Toast.makeText(this, "高级功能已开启", Toast.LENGTH_SHORT).show();
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastVersionTapMs > 3000) versionTapCount = 0;
        lastVersionTapMs = now;
        if (++versionTapCount >= 4) {
            advancedUnlocked = true;
            versionTapCount = 0;
            Toast.makeText(this, "高级功能已开启，本次运行有效", Toast.LENGTH_SHORT).show();
            showAdvanced();
        }
    }



    /** 设置页切换「副屏悬浮窗」。 */
    void onSecondaryScreenChanged(boolean enabled) {
        if (enabled && !AndroidCompat.canDrawOverlays(this)) {
            Toast.makeText(this, "副屏需要「显示在其他应用上层」权限，请先授权", Toast.LENGTH_LONG).show();
            openOverlayPermission();
        }
        ConnectionService service = connectionService;
        if (service != null) service.refreshSecondaryScreen();
    }

    /** 设置页切换副屏大小档位；手动拖动缩放过的窗口会被重置为预设宽度。 */
    void onSecondaryScreenScaleChanged() {
        ConnectionService service = connectionService;
        if (service != null) service.applySecondaryScreenScale();
    }

    void showSecondaryScreenHelp() {
        new AlertDialog.Builder(this)
                .setTitle("副屏悬浮窗")
                .setMessage("原版用系统真实存在的第二块屏幕（仪表屏）显示副屏画面。"
                        + "本功能改用悬浮窗模拟副屏，把盒子下发的副屏视频流（CMD 44）"
                        + "渲染到可拖动、可缩放的窗口里。"
                        + "\n\n同时叠加盒子上报的导航与歌词文字（CMD 42）："
                        + "上方显示「距离 + 路名」和「剩余时间 | 剩余距离」并带转向箭头，"
                        + "下方显示当前歌词（无歌词时显示歌名 · 歌手）。"
                        + "文字使用半透明黑底，不会遮挡盒子自己绘制的 HUD。"
                        + "\n\n拖动窗口可移动位置；右下角「◢」拖动改变宽度，高度按画面比例自动调整；"
                        + "右上角「×」关闭（会同时关闭本开关）。位置与大小会保存。"
                        + "\n\n需要「显示在其他应用上层」权限。窗口只负责显示，不转发触摸——"
                        + "副屏坐标属于另一块屏，直接按主屏坐标发送会被盒子误判。"
                        + "\n\n导航数据要求手机处于真实路线引导中且 CarPlay 在前台；"
                        + "歌词依赖 CarPlay NowPlaying。没有数据时窗口会停在「等待盒子画面」。")
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---- 盒子指令日志窗口 ----

    void onBoxLogOverlayChanged(boolean enabled) {
        if (enabled && !AndroidCompat.canDrawOverlays(this)) {
            Toast.makeText(this, "日志窗口需要「显示在其他应用上层」权限，请先授权",
                    Toast.LENGTH_LONG).show();
            openOverlayPermission();
        }
        ConnectionService service = connectionService;
        if (service != null) service.refreshBoxLogOverlay();
    }

    void showBoxLogOverlayHelp() {
        new AlertDialog.Builder(this)
                .setTitle("盒子指令日志窗口")
                .setMessage("用悬浮窗实时显示盒子上报的命令（盒子 → 主机），"
                        + "用于排查连接、投屏与副屏问题。"
                        + "\n\n每行格式：时间  命令号  协议名  负载字节数  首个整数值"
                        + "（连接阶段 / 子命令 / 升级状态 / 手机类型）。"
                        + "\n\n视频帧（6）、音频帧（7）、副屏帧（44）数量很大，"
                        + "按每秒汇总成一行计数，避免刷屏；其他控制命令逐条显示。"
                        + "\n\n拖动标题栏可移动；「清空」丢弃已记录的日志；"
                        + "「×」关闭窗口并同时关闭本开关。保留最近 200 条，最新在最上面。"
                        + "\n\n日志只在本次应用运行期间保留，服务销毁后清空。"
                        + "需要「显示在其他应用上层」权限。")
                .setPositiveButton("知道了", null)
                .show();
    }

    void onHeadlightTestOverlayChanged(boolean enabled) {
        if (enabled && !AndroidCompat.canDrawOverlays(this)) {
            Toast.makeText(this, "大灯模拟窗口需要「显示在其他应用上层」权限，请先授权",
                    Toast.LENGTH_LONG).show();
            openOverlayPermission();
        }
        ConnectionService service = connectionService;
        if (service != null) service.refreshHeadlightTestOverlay();
    }

    void showHeadlightTestOverlayHelp() {
        new AlertDialog.Builder(this)
                .setTitle("大灯模拟悬浮窗")
                .setMessage("「夜间模式 - 大灯联动」依赖车机的 McuManager 服务读取大灯状态，"
                        + "普通手机上没有该服务，无法测试。"
                        + "\n\n打开本开关后会出现一个悬浮窗，点「大灯开 / 大灯关」伪造大灯状态，"
                        + "后续「回调 → 下发盒子 cmd 8/16（夜间）或 cmd 8/17（白天）→ "
                        + "CarPlay 昼夜切换」与真实大灯完全一致。"
                        + "\n\n使用前提：设置 → 夜间模式选「大灯联动」，且盒子已连接。"
                        + "悬浮窗会显示当前条件是否满足。"
                        + "\n\n拖动标题栏可移动；「×」关闭窗口并同时关闭本开关。"
                        + "需要「显示在其他应用上层」权限。")
                .setPositiveButton("知道了", null)
                .show();
    }

    void clearBoxCommandLog() {
        ConnectionService service = connectionService;
        if (service == null) {
            Toast.makeText(this, "连接服务尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        service.clearBoxCommandLog();
    }

    void openOverlayPermission() {
        Intent intent = new Intent(Build.VERSION.SDK_INT >= 23
                ? android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                : android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException exception) {
            android.util.Log.e("AutoPlay", "Overlay permission settings unavailable", exception);
            Toast.makeText(this, "此设备未提供悬浮窗授权页面，请在系统应用设置中查看",
                    Toast.LENGTH_LONG).show();
        }
    }

    void showSteeringLearning() {
        showSettingsPageOf(PAGE_STEERING);
    }
    void showNavigationLog() {
        showSettingsPageOf(PAGE_NAVIGATION);
    }

    String navigationLogText() {
        return connectionService == null ? "连接服务尚未就绪，等待连接…"
                : connectionService.navigationLogText();
    }

    /** 「仪表导航」页顶部的当前状态：导航文字 + 歌词 + 子类型诊断。 */
    String dashboardText() {
        ConnectionService service = connectionService;
        if (service == null) {
            return "连接服务尚未就绪，等待连接…";
        }
        return service.navigationText() + "\n" + service.maneuverDebugText()
                + "\n\n歌词\n" + service.mediaText()
                + "\n\n" + service.dashboardSubtypeSummary();
    }

    String boxNameText(boolean bluetooth) {
        if (connectionService == null || connectionService.getSession() == null) {
            return "未连接盒子";
        }
        String name = bluetooth ? connectionService.boxBluetoothName : connectionService.boxWifiName;
        return name == null ? "尚未收到盒子上报" : name;
    }

    void clearNavigationLog() {
        if (connectionService == null) {
            Toast.makeText(this, "连接服务尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        connectionService.clearNavigationLog();
    }

    void showEnvironmentCheck() {
        if (Build.VERSION.SDK_INT >= 23 && !AndroidCompat.hasMicrophone(this)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_ENV_MICROPHONE);
        }
        showSettingsPageOf(PAGE_ENVIRONMENT);
    }

    private void fadeIn(View view) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(160).start();
    }

    private void showSettingsPageOf(int page) {
        if (page == PAGE_ADVANCED && !advancedUnlocked) page = PAGE_MORE;
        settingsVisible = true;
        settingsPage = page;
        View view;
        if (page == PAGE_MORE) {
            view = screens.morePage();
        } else if (page == PAGE_ADVANCED) {
            view = screens.advancedPage();
        } else if (page == PAGE_ENVIRONMENT) {
            view = screens.environmentPage();
        } else if (page == PAGE_NAVIGATION) {
            view = screens.navigationPage();
        } else if (page == PAGE_STEERING) {
            view = screens.steeringPage();
        } else {
            view = screens.settingsPage();
        }
        setContentView(view);
        fadeIn(view);
    }

    interface BooleanListener {
        void onChanged(boolean value);
    }

    interface IntListener {
        void onChanged(int value);
    }

    String[] resolutionChoices() {
        int[] screen = physicalVideoSize();
        java.util.List<String> choices = VideoOptions.resolutions(screen[0], screen[1]);
        String selected = requestedVideoSizeLabel();
        if (!choices.contains(selected)) choices.add(selected);
        return choices.toArray(new String[0]);
    }

    String requestedVideoSizeLabel() {
        int[] size = getRequestedVideoSize();
        return size[0] + "x" + size[1];
    }

    void onVideoSettingsChanged() {
        int[] size = getRequestedVideoSize();
        videoRenderer.setFrameRate(VideoPreferences.frameRate(getPreferences(MODE_PRIVATE)));
        if (connectionService != null) {
            connectionService.updateVideoSettings(size[0], size[1]);
        }
        Toast.makeText(this, "已更新显示参数；盒子或手机可能需要重连后生效",
                Toast.LENGTH_LONG).show();
    }

    String appVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "未知" : version;
        } catch (PackageManager.NameNotFoundException exception) {
            return "未知";
        }
    }

    int appVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException exception) {
            return 0;
        }
    }

    // ---- 在线更新 ----

    /** 首选通道：GitHub Releases API，弹窗正文直接展示 Release 的描述内容。 */
    private static final String UPDATE_RELEASE_API_URL =
            "https://api.github.com/repos/CuiYao631/playauto/releases/latest";

    /** 备用通道：Release 接口限流或不可用时退回仓库内的版本清单。 */
    private static final String UPDATE_CHECK_URL =
            "https://raw.githubusercontent.com/CuiYao631/playauto/main/update.json";

    void showUpdateCheck() {
        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show();
        UpdateChecker.checkLatestRelease(UPDATE_RELEASE_API_URL, appVersion(), appVersionCode(),
                new UpdateChecker.Callback() {
                    @Override
                    public void onUpdateAvailable(UpdateChecker.UpdateInfo info) {
                        showUpdateDialog(info);
                    }

                    @Override
                    public void onNoUpdate() {
                        Toast.makeText(MainActivity.this, "已是最新版本", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        Log.w("MainActivity", "Release 接口不可用，改用版本清单：" + message);
                        checkUpdateManifest();
                    }
                });
    }

    private void checkUpdateManifest() {
        UpdateChecker.check(UPDATE_CHECK_URL, appVersion(), appVersionCode(),
                new UpdateChecker.Callback() {
                    @Override
                    public void onUpdateAvailable(UpdateChecker.UpdateInfo info) {
                        showUpdateDialog(info);
                    }

                    @Override
                    public void onNoUpdate() {
                        Toast.makeText(MainActivity.this, "已是最新版本", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void showUpdateDialog(UpdateChecker.UpdateInfo info) {
        String message = "发现新版本 " + info.version
                + "\n\n更新内容：\n" + UpdateChecker.formatNotes(info.notes);
        new AlertDialog.Builder(this)
                .setTitle("软件更新")
                .setMessage(message)
                .setPositiveButton("立即更新", (dialog, which) -> downloadAndInstall(info.url))
                .setNegativeButton("稍后", null)
                .show();
    }

    // ---- 盒子固件更新 ----

    /** 盒子固件服务端基址，与原版一致。 */
    private static final String BOX_FIRMWARE_BASE_URL = BoxFirmwareChecker.DEFAULT_BASE_URL;

    String boxFirmwareVersion() {
        ConnectionService service = connectionService;
        return service == null ? null : service.boxVersion;
    }

    void showBoxFirmwareCheck() {
        ConnectionService service = connectionService;
        if (service == null) {
            Toast.makeText(this, "请先连接盒子", Toast.LENGTH_SHORT).show();
            return;
        }
        String boxVersion = service.boxVersion;
        if (boxVersion == null || boxVersion.isEmpty()) {
            Toast.makeText(this, "尚未获取盒子固件版本，请稍候重试", Toast.LENGTH_LONG).show();
            return;
        }
        if (service.boxUuid == null || service.boxUuid.isEmpty()) {
            Toast.makeText(this, "尚未获取盒子信息（uuid/型号），请重新插拔盒子",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "正在检查盒子固件…", Toast.LENGTH_SHORT).show();
        BoxFirmwareChecker.check(
                BOX_FIRMWARE_BASE_URL,
                "",
                appVersion(),
                boxVersion,
                service.boxUuid,
                service.boxManufacturer,
                service.boxProductType,
                service.boxFirmwareName == null || service.boxFirmwareName.isEmpty()
                        ? BoxFirmwareChecker.FIRMWARE_FILE_NAME : service.boxFirmwareName,
                new BoxFirmwareChecker.Callback() {
                    @Override
                    public void onResult(BoxFirmwareChecker.Info info, String token) {
                        if (info == null) {
                            Toast.makeText(MainActivity.this,
                                    "盒子固件已是最新版本（" + boxVersion + "）",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        showBoxFirmwareDialog(info, token);
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void showBoxFirmwareDialog(BoxFirmwareChecker.Info info, String token) {
        StringBuilder message = new StringBuilder();
        message.append("当前版本：").append(boxFirmwareVersion()).append('\n');
        message.append("最新版本：").append(info.version).append('\n');
        if (info.size > 0) {
            message.append("包大小：")
                    .append(String.format(java.util.Locale.ROOT, "%.1f MB",
                            info.size / 1024.0 / 1024.0))
                    .append('\n');
        }
        if (info.forced) {
            message.append("\n⚠ 该版本为强制升级\n");
        }
        if (info.notes != null && !info.notes.isEmpty()) {
            message.append("\n更新说明：\n").append(info.notes);
        }
        message.append("\n\n升级过程中请勿拔出盒子或断电。");

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("盒子固件更新")
                .setMessage(message.toString())
                .setPositiveButton("下载并升级", (dialog, which) -> downloadBoxFirmware(info, token));
        if (!info.forced) {
            builder.setNegativeButton("稍后", null);
        }
        builder.show();
    }

    private void downloadBoxFirmware(BoxFirmwareChecker.Info info, String token) {
        ProgressBar progressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);

        TextView statusText = Ui.text(this, "正在下载固件…", 14, Ui.TEXT, false);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(Ui.dp(this, 32), Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24));
        layout.addView(statusText);
        layout.addView(progressBar);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("下载盒子固件")
                .setView(layout)
                .setCancelable(false)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();

        BoxFirmwareChecker.download(BOX_FIRMWARE_BASE_URL, token, info,
                getCacheDir(), new BoxFirmwareChecker.DownloadCallback() {
                    @Override
                    public void onProgress(long downloaded, long total) {
                        if (!dialog.isShowing()) return;
                        if (total > 0) {
                            progressBar.setProgress((int) (downloaded * 100 / total));
                        }
                        statusText.setText(String.format(java.util.Locale.ROOT,
                                "已下载 %.1f / %.1f MB",
                                downloaded / 1024.0 / 1024.0, total / 1024.0 / 1024.0));
                    }

                    @Override
                    public void onSuccess(File file) {
                        if (dialog.isShowing()) dialog.dismiss();
                        applyBoxFirmware(file);
                    }

                    @Override
                    public void onError(String message) {
                        if (dialog.isShowing()) dialog.dismiss();
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void applyBoxFirmware(File file) {
        ConnectionService service = connectionService;
        if (service == null) {
            Toast.makeText(this, "盒子已断开，无法升级", Toast.LENGTH_LONG).show();
            return;
        }
        byte[] image;
        try {
            image = readAllBytes(file);
        } catch (IOException exception) {
            Toast.makeText(this, "读取固件失败：" + exception.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (image.length > BoxProtocolSession.MAX_FIRMWARE_SIZE) {
            Toast.makeText(this, "固件包超过 50MB 上限", Toast.LENGTH_LONG).show();
            return;
        }
        boolean sent = service.sendFirmwareImage(image);
        if (sent) {
            firmwareSuppressed = false;
            firmwareShownProgress = 0;
            enterFirmwareUpgrading();
        } else {
            Toast.makeText(this, "固件下发失败，请查看连接状态", Toast.LENGTH_LONG).show();
        }
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    // ---- 盒子固件升级页面 ----

    /**
     * 固件升级专用页面：全屏展示进度，升级成功后等待盒子重启再自动退出。
     *
     * <p>进入条件由 {@link #syncFirmwareState} 决定：盒子开始上报 187/177，
     * 或本应用主动下发固件成功后立即进入。
     */
    private void showFirmwarePage() {
        if (firmwarePageVisible) {
            updateFirmwarePageUi();
            return;
        }
        firmwarePageVisible = true;
        if (firmwarePage == null) {
            firmwarePage = new UpgradeProgressPage(this, "盒子固件升级",
                    "升级过程中请勿拔出盒子或断开电源");
            firmwarePage.setExitText("退出升级页");
            firmwarePage.setOnExit(this::exitFirmwarePage);
        }
        View page = firmwarePage.root();
        setContentView(page);
        fadeIn(page);
        updateFirmwarePageUi();
    }

    private void updateFirmwarePageUi() {
        if (!firmwarePageVisible || firmwarePage == null) {
            return;
        }
        firmwarePage.setIndeterminate(false);
        firmwarePage.setProgress(firmwareShownProgress < 0 ? 0 : firmwareShownProgress);

        switch (firmwarePagePhase) {
            case FIRMWARE_UPGRADING:
                firmwarePage.setStatus("正在升级盒子固件，请保持连接", Ui.TEXT);
                firmwarePage.setHint("升级完成后盒子会自动重启，请耐心等待");
                firmwarePage.setExitVisible(false);
                break;
            case FIRMWARE_WAIT_REBOOT:
                firmwarePage.setProgress(100);
                firmwarePage.setStatus("升级成功，正在重启盒子…", Ui.ACCENT);
                firmwarePage.setHint("已向盒子下发重启指令，重新连接后会自动退出本页面");
                firmwarePage.setExitVisible(true);
                break;
            case FIRMWARE_FAILED:
                firmwarePage.setStatus("升级失败", Ui.ERROR);
                firmwarePage.setExitVisible(true);
                break;
            default:
                firmwarePage.setStatus("正在准备升级…", Ui.TEXT);
                firmwarePage.setExitVisible(false);
                break;
        }
    }

    private void enterFirmwareUpgrading() {
        firmwarePagePhase = FIRMWARE_UPGRADING;
        if (firmwareShownProgress < 0) firmwareShownProgress = 0;
        uiHandler.removeCallbacks(firmwareRebootTimeout);
        showFirmwarePage();
    }

    private void enterFirmwareWaitReboot() {
        firmwarePagePhase = FIRMWARE_WAIT_REBOOT;
        firmwareShownProgress = 100;
        firmwareSawDisconnect = false;
        showFirmwarePage();
        updateFirmwarePageUi();
        uiHandler.removeCallbacks(firmwareRebootTimeout);
        uiHandler.postDelayed(firmwareRebootTimeout, FIRMWARE_REBOOT_TIMEOUT_MS);
    }

    private void enterFirmwareFailed(String message) {
        firmwarePagePhase = FIRMWARE_FAILED;
        showFirmwarePage();
        if (firmwarePage != null) firmwarePage.setHint(message);
        updateFirmwarePageUi();
        uiHandler.removeCallbacks(firmwareRebootTimeout);
    }

    private void setFirmwareProgress(int progress) {
        firmwareShownProgress = Math.max(0, Math.min(100, progress));
        updateFirmwarePageUi();
    }

    /** 升级流程结束，提示后返回之前的页面。 */
    private void finishFirmwarePage(String message) {
        uiHandler.removeCallbacks(firmwareRebootTimeout);
        firmwarePagePhase = FIRMWARE_IDLE;
        firmwareSawDisconnect = false;
        firmwareShownProgress = -1;
        firmwareSuppressed = true;
        firmwarePageVisible = false;
        if (message != null && !message.isEmpty()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        if (settingsVisible) {
            showSettingsPageOf(settingsPage);
        } else {
            showMain();
        }
    }

    private void exitFirmwarePage() {
        if (firmwarePagePhase == FIRMWARE_UPGRADING) {
            Toast.makeText(this, "升级进行中，请勿退出", Toast.LENGTH_SHORT).show();
            return;
        }
        finishFirmwarePage(null);
    }

    /**
     * 根据服务层上报的固件状态推进页面。
     *
     * <p>状态来源：盒子 187 命令（1 开始 / 2 成功 / 3 失败 / 5~7 OTA）与 177 进度命令。
     */
    private void syncFirmwareState(ConnectionService service) {
        if (service == null) {
            return;
        }
        int status = service.firmwareStatus;
        int progress = service.firmwareProgress;
        boolean sessionAlive = service.getSession() != null;

        if (firmwareSuppressed) {
            if (status == 0 && progress < 0) {
                firmwareSuppressed = false;
            } else if (status == 1 || status == 5) {
                firmwareSuppressed = false;
            } else {
                return;
            }
        }

        if (firmwarePagePhase == FIRMWARE_IDLE) {
            if (status == 1 || status == 5 || progress >= 0) {
                enterFirmwareUpgrading();
                if (progress >= 0) setFirmwareProgress(progress);
            } else if (status == 2 || status == 6) {
                enterFirmwareWaitReboot();
            } else if (status == 3 || status == 7) {
                enterFirmwareFailed("盒子报告升级失败，请稍后重试或使用 U 盘升级");
            }
            return;
        }

        if (firmwarePagePhase == FIRMWARE_UPGRADING) {
            if (progress >= 0) setFirmwareProgress(progress);
            if (status == 2 || status == 6) {
                enterFirmwareWaitReboot();
            } else if (status == 3 || status == 7) {
                enterFirmwareFailed("盒子报告升级失败，请稍后重试或使用 U 盘升级");
            } else if (!sessionAlive) {
                enterFirmwareFailed("升级过程中盒子连接中断，请重新插拔盒子后确认固件版本");
            }
            return;
        }

        if (firmwarePagePhase == FIRMWARE_WAIT_REBOOT) {
            if (!sessionAlive) {
                // 盒子重启会先断开 USB。
                firmwareSawDisconnect = true;
            } else if (firmwareSawDisconnect && service.boxVersion != null
                    && !service.boxVersion.isEmpty()) {
                finishFirmwarePage("升级完成，盒子已重启（固件 " + service.boxVersion + "）");
            }
        }
    }

    // ---- 软件更新页面 ----

    /** 进入全屏更新页面（与盒子固件升级页同一套样式）。 */
    private void showAppUpdatePage() {
        if (appUpdatePageVisible) {
            updateAppUpdatePageUi();
            return;
        }
        appUpdatePageVisible = true;
        if (appUpdatePage == null) {
            appUpdatePage = new UpgradeProgressPage(this, "软件更新",
                    "下载过程中请保持网络连接，请勿退出");
            appUpdatePage.setExitText("退出更新页");
            appUpdatePage.setOnExit(this::exitAppUpdatePage);
        }
        View page = appUpdatePage.root();
        setContentView(page);
        fadeIn(page);
        updateAppUpdatePageUi();
    }

    private void updateAppUpdatePageUi() {
        if (!appUpdatePageVisible || appUpdatePage == null) {
            return;
        }
        switch (appUpdatePhase) {
            case APP_UPDATE_DOWNLOADING:
                appUpdatePage.setIndeterminate(false);
                appUpdatePage.setStatus("正在下载新版本，请保持网络连接", Ui.TEXT);
                appUpdatePage.setHint("下载完成后会自动打开系统安装程序");
                appUpdatePage.setExitVisible(false);
                break;
            case APP_UPDATE_READY:
                appUpdatePage.setIndeterminate(false);
                appUpdatePage.setProgress(100);
                appUpdatePage.setStatus("下载完成，准备安装", Ui.ACCENT);
                appUpdatePage.setHint("即将打开系统安装程序，请按提示完成安装");
                appUpdatePage.setExitVisible(true);
                break;
            case APP_UPDATE_INSTALLING:
                appUpdatePage.setIndeterminate(true);
                appUpdatePage.setPercentText("安装中");
                appUpdatePage.setStatus("已打开系统安装程序", Ui.ACCENT);
                appUpdatePage.setHint("请在弹出的系统界面中确认安装；取消安装会回到本页面");
                appUpdatePage.setExitVisible(true);
                break;
            case APP_UPDATE_FAILED:
                appUpdatePage.setIndeterminate(false);
                appUpdatePage.setStatus("更新失败", Ui.ERROR);
                appUpdatePage.setHint(appUpdateFailure);
                appUpdatePage.setExitVisible(true);
                break;
            default:
                appUpdatePage.setIndeterminate(false);
                appUpdatePage.setProgress(0);
                appUpdatePage.setStatus("正在准备下载…", Ui.TEXT);
                appUpdatePage.setExitVisible(false);
                break;
        }
    }

    private void setAppUpdateProgress(int percent) {
        if (appUpdatePage == null) return;
        appUpdatePage.setIndeterminate(false);
        appUpdatePage.setProgress(percent);
    }

    private void failAppUpdate(String message) {
        appUpdateFailure = message;
        appUpdatePhase = APP_UPDATE_FAILED;
        showAppUpdatePage();
        updateAppUpdatePageUi();
    }

    /** 关闭更新页面，回到进入前的界面。 */
    private void finishAppUpdatePage(String toast) {
        appUpdatePageVisible = false;
        appUpdatePhase = APP_UPDATE_IDLE;
        appUpdateFailure = "";
        if (toast != null && !toast.isEmpty()) {
            Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
        }
        if (settingsVisible) {
            showSettingsPageOf(settingsPage);
        } else {
            showMain();
        }
    }

    private void exitAppUpdatePage() {
        if (appUpdatePhase == APP_UPDATE_DOWNLOADING) {
            Toast.makeText(this, "正在下载更新，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        finishAppUpdatePage(null);
    }

    private void downloadAndInstall(String downloadUrl) {
        appUpdatePhase = APP_UPDATE_DOWNLOADING;
        showAppUpdatePage();
        setAppUpdateProgress(0);
        updateAppUpdatePageUi();

        new AsyncTask<Void, Integer, Object>() {
            private String failure;

            @Override
            protected Object doInBackground(Void... params) {
                FileOutputStream fos = null;
                InputStream in = null;
                try {
                    File filesDir = getFilesDir();
                    File apkFile = new File(filesDir, "update.apk");
                    if (apkFile.exists()) apkFile.delete();

                    URL url = new URL(downloadUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestMethod("GET");

                    int total = conn.getContentLength();
                    in = conn.getInputStream();
                    fos = new FileOutputStream(apkFile);

                    byte[] buf = new byte[8192];
                    int read;
                    long downloaded = 0;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            publishProgress((int) (downloaded * 100 / total));
                        }
                    }
                    fos.close();
                    fos = null;
                    in.close();
                    in = null;
                    conn.disconnect();

                    if (downloaded <= 0) {
                        failure = "下载内容为空，请稍后重试";
                        return null;
                    }
                    return apkFile;
                } catch (Exception e) {
                    android.util.Log.e("AutoPlay", "download error", e);
                    failure = "下载失败：" + e.getMessage();
                    return null;
                } finally {
                    try { if (fos != null) fos.close(); } catch (IOException ignored) {}
                    try { if (in != null) in.close(); } catch (IOException ignored) {}
                }
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                setAppUpdateProgress(values[0]);
            }

            @Override
            protected void onPostExecute(Object result) {
                if (result instanceof File) {
                    appUpdatePhase = APP_UPDATE_READY;
                    updateAppUpdatePageUi();
                    installApk((File) result);
                } else {
                    failAppUpdate(failure == null ? "下载失败，请稍后重试" : failure);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void installApk(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Build.VERSION.SDK_INT >= 24) {
            Uri uri = UpdateFileProvider.getUriForFile(this,
                    "cn.autoplay.replacement.update", apkFile);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(Uri.fromFile(apkFile),
                    "application/vnd.android.package-archive");
        }

        try {
            startActivityForResult(intent, REQUEST_INSTALL_PACKAGE);
            appUpdatePhase = APP_UPDATE_INSTALLING;
            updateAppUpdatePageUi();
        } catch (Exception e) {
            android.util.Log.e("AutoPlay", "install error", e);
            failAppUpdate("无法打开安装程序：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_PACKAGE) {
            // 安装成功时进程会被替换；能回到这里说明用户取消了安装。
            if (appUpdatePageVisible) {
                finishAppUpdatePage("已取消安装");
            }
        }
    }

    String boxVersion() {
        return boxVersion;
    }

    Bitmap carLogoBitmap() {
        return loadCarLogoBitmap(selectedCarLogo);
    }

    private void refreshConnectionSettings() {
        if (connectionService != null) connectionService.refreshSettings();
    }

    void onBoxSettingsChanged() {
        if (connectionService != null) connectionService.refreshSettings();
        Toast.makeText(this, protocolSession == null
                ? "已保存，盒子握手成功后发送；部分设置需重连手机" : "已更新配置，部分设置需重连手机生效",
                Toast.LENGTH_LONG).show();
    }

    void onDecoderChanged() {
        int mode = getPreferences(MODE_PRIVATE).getInt("decoder_mode", VideoOptions.HARDWARE);
        videoRenderer.setDecoderMode(mode);
        videoError = null;
        connectionStatus.setText("盒子：已切换解码方式，等待关键帧");
        Toast.makeText(this, mode == 3 ? "已切换原版 OpenH264，固定使用 OpenGL ES 2.0 渲染"
                        : "已切换解码方式；设备不支持时会报告解码错误",
                Toast.LENGTH_LONG).show();
        onVideoSettingsChanged();
        showSettings();
    }

    void onSteeringProtocolChanged() {
        screens.cancelSteeringLearning();
        vendorKeys.reset();
        screens.clearSteeringLog();
        showSteeringLearning();
    }

    void onRenderChanged() {
        Toast.makeText(this, getPreferences(MODE_PRIVATE).getInt("decoder_mode", 0) == 3
                        ? "系统解码渲染设置已保存；原版 OpenH264 固定使用 OpenGL ES 2.0"
                        : "已切换渲染方式，等待下一关键帧", Toast.LENGTH_LONG).show();
    }

    void sendMediaCommand(int command) {
        if (protocolSession == null || !protocolSession.sendMediaCommand(command)) {
            Toast.makeText(this, "手机尚未连接，无法发送媒体控制命令", Toast.LENGTH_LONG).show();
        }
    }

    void updateBackgroundControl() {
        if (connectionService != null) connectionService.updateMediaSession();
    }

    void onBackgroundConnectionChanged(boolean enabled) {
        if (connectionService != null) connectionService.updateBackgroundPolicy();
        if (enabled && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CONNECTION_NOTIFICATIONS);
        }
        Toast.makeText(this, enabled
                ? "后台连接已开启；连接盒子后显示常驻通知，请允许车机后台运行"
                : "后台连接已关闭，退出页面后不再保证保持连接", Toast.LENGTH_LONG).show();
    }

    void showRebootAfterUpgradeHelp() {
        new AlertDialog.Builder(this)
                .setTitle("升级成功后重启盒子")
                .setMessage("固件升级成功（盒子回报状态 2 或 6）后，PlayAuto 会在约 1.5 秒后"
                        + "向盒子下发重启指令（CMD 206），让新固件立即生效。"
                        + "\n\n部分盒子固件升级完会自行重启。此时指令可能因为 USB 已断开"
                        + "而写入失败，属正常现象，不会提示错误。"
                        + "\n\n重启期间投屏会中断，盒子重新枚举后自动恢复连接。"
                        + "如果升级后出现异常，可在下方「立即重启盒子」手动重试。")
                .setPositiveButton("知道了", null)
                .show();
    }

    void onRebootBoxTapped() {
        ConnectionService service = connectionService;
        if (service == null) {
            Toast.makeText(this, "请先连接盒子", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("立即重启盒子")
                .setMessage("重启会让投屏中断数秒，盒子重新连接后自动恢复。确定重启？")
                .setPositiveButton("重启", (dialog, which) -> {
                    boolean sent = service.rebootBox();
                    Toast.makeText(this, sent
                            ? "已发送重启指令，等待盒子重新连接"
                            : "重启指令未送达，请检查盒子连接",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 主动断开当前连接的手机（{@code CMD_MANUAL_DISCONNECT} 15）。
     *
     * <p>零负载命令，由盒子执行断开。断开后协议层会压制自动回连一段时间，
     * 避免「刚断开又被自动连接拉回来」；需要恢复时用「指定回连手机」点名即可提前解除。
     */
    void onManualDisconnectTapped() {
        ConnectionService service = connectionService;
        if (service == null) {
            Toast.makeText(this, "请先连接盒子", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("断开当前连接的手机")
                .setMessage("会让盒子立刻断开当前手机，投屏会中断。"
                        + "\n\n断开后 "
                        + (BoxProtocolSession.MANUAL_DISCONNECT_COOLDOWN_MS / 1000)
                        + " 秒内不会自动回连，避免刚断开又被连上。"
                        + "需要恢复时可在手机上重新连接，或用下方「指定回连手机」点名。")
                .setPositiveButton("断开", (dialog, which) -> {
                    boolean sent = service.manualDisconnectPhone();
                    Toast.makeText(this, sent
                            ? "已要求盒子断开手机，等待手机退出"
                            : "断开指令未送达，请检查盒子连接",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 选择「点名回连」的手机（{@code CMD_AUTO_CONN_BT_ADDR} 17）。
     *
     * <p>列表来自盒子 {@code CMD_BLUETOOTH_PAIR_LIST}(18) 的上报。与「自动连接」
     * 不同：自动连接用 {@code 8/1002} 让盒子自己挑，多台已配对手机时可能挑错。
     */
    void showAutoConnectDevicePicker() {
        ConnectionService service = connectionService;
        if (service == null) {
            Toast.makeText(this, "请先连接盒子", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<BoxProtocolSession.PairedDevice> devices = service.pairedDevices();
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("指定回连手机")
                    .setMessage("盒子还没有上报已配对手机列表。请确认手机上已完成过一次"
                            + "与盒子蓝牙的配对，然后重新插拔盒子。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String[] labels = new String[devices.size()];
        for (int index = 0; index < devices.size(); index++) {
            labels[index] = devices.get(index).toString();
        }
        new AlertDialog.Builder(this)
                .setTitle("指定回连手机")
                .setItems(labels, (dialog, which) -> {
                    BoxProtocolSession.PairedDevice device = devices.get(which);
                    boolean sent = service.autoConnectToDevice(device.getMac());
                    Toast.makeText(this, sent
                            ? "已请求盒子回连 " + device
                            : "指定回连指令未送达，请检查盒子连接",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 删除盒子里的一台已配对手机（{@code CMD_BLUETOOTH_PAIR_LIST} 18 回写）。
     *
     * <p>盒子以主机下发的列表为准，因此删除等价于「把去掉这一台之后的新列表写回盒子」。
     */
    void showPairedDeviceRemoval() {
        ConnectionService service = connectionService;
        if (service == null) {
            Toast.makeText(this, "请先连接盒子", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<BoxProtocolSession.PairedDevice> devices = service.pairedDevices();
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("删除已配对手机")
                    .setMessage("盒子还没有上报已配对手机列表。请确认手机上已完成过一次"
                            + "与盒子蓝牙的配对，然后重新插拔盒子。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String[] labels = new String[devices.size()];
        for (int index = 0; index < devices.size(); index++) {
            labels[index] = devices.get(index).toString();
        }
        new AlertDialog.Builder(this)
                .setTitle("删除已配对手机")
                .setItems(labels, (dialog, which) -> {
                    BoxProtocolSession.PairedDevice device = devices.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle("删除配对")
                            .setMessage("将从盒子的已配对列表中删除：\n" + device
                                    + "\n\n删除后盒子不会再自动回连这台手机，"
                                    + "需要时可在手机上重新配对。")
                            .setPositiveButton("删除", (confirm, ignored) -> {
                                boolean sent = service.removePairedDevice(device.getMac());
                                Toast.makeText(this, sent
                                        ? "已从盒子删除 " + device
                                        : "删除失败，请检查盒子连接",
                                        Toast.LENGTH_LONG).show();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void showBackgroundConnectionHelp() {
        new AlertDialog.Builder(this)
                .setTitle("后台连接")
                .setMessage("默认开启。连接盒子后，前台服务通过常驻通知维持连接和音频；"
                        + "切换到其他应用或重新打开页面时，不主动断开盒子。返回后恢复投屏画面。"
                        + "\n\n请在车机的电池管理中允许 PlayAuto 后台运行。"
                        + "需要后台通话或语音时，请先在 PlayAuto 内授予麦克风权限。"
                        + "\n\n没有连接盒子时不启动常驻服务。拔线、断电、系统强行停止或终止进程仍会断开连接。")
                .setPositiveButton("知道了", null)
                .show();
    }

    void playTestTone() {
        if (testTone != null) {
            testTone.release();
        }
        try {
            testTone = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 30);
            if (!testTone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 350)) {
                Toast.makeText(this, "无法播放测试音，请检查音频输出", Toast.LENGTH_LONG).show();
            }
        } catch (RuntimeException exception) {
            android.util.Log.e("AutoPlay", "Failed to play test tone", exception);
            Toast.makeText(this, "测试音失败：" + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void onNavigationBarChanged() {
        boolean hidden = getPreferences(MODE_PRIVATE).getBoolean("hide_navigation", false)
                || projectionMode && isFullscreenApp();
        View decor = getWindow().getDecorView();
        int mask = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        int flags = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(hidden ? flags | mask : flags & ~mask);
    }

    void onBootAutoStartChanged(boolean enabled) {
        if (!enabled) {
            cancelBootNotification();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_BOOT_NOTIFICATIONS);
        } else {
            showBootAutoStartHelp();
        }
    }

    void showBootAutoStartHelp() {
        new AlertDialog.Builder(this)
                .setTitle("开机自动启动")
                .setMessage("开启后，在车机开机并解锁后启动 PlayAuto。请在车机系统的自启动/电池管理中允许本应用运行。"
                        + "\n\nAndroid 10 及以上限制后台弹出界面；没有系统允许的悬浮窗权限时，将显示启动通知，点击即可打开。"
                        + "请保持通知权限开启。部分车机还需要厂家提供自启动白名单。"
                        + "\n\n首次安装后需手动打开应用。强行停止应用后，需再次手动打开才能恢复接收开机广播。"
                        + "此功能不包含 USB 插入启动或手机连接后自动回到应用。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /** Android 键码转状态机标识，与厂家广播码（如 {@code "VR"}）共存于同一套字符串键。 */
    private static String androidKeyId(int keyCode) {
        return "key:" + keyCode;
    }

    private void cancelBootNotification() {
        android.app.NotificationManager manager = (android.app.NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(BootStartReceiver.NOTIFICATION_ID);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int command = connectionService == null ? -1
                : connectionService.mediaCommand(event.getKeyCode());
        int longCommand = connectionService == null ? -1
                : connectionService.mediaCommand(event.getKeyCode(), true);
        if (settingsVisible && settingsPage == PAGE_STEERING) {
            screens.recordSteeringKey(event, command);
        }
        if (settingsVisible && settingsPage == PAGE_STEERING || command >= 0) {
            SettingsScreens.logSteeringKey("activity", event, command);
        }
        if (settingsVisible && settingsPage == PAGE_STEERING
                && screens.handleSteeringKey(event)) {
            return true;
        }
        if ((command >= 0 || longCommand >= 0) && phonePlugged && protocolSession != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    if (longCommand < 0) {
                        // 只绑定了单击：按下立即执行，保持原有响应速度
                        return connectionService != null
                                && connectionService.sendSteeringCommand(command, "android");
                    }
                    // 已绑定长按：占位后启动计时器，满 800ms 自动触发，无需等松手
                    uiHandler.removeCallbacks(steeringLongPressTask);
                    steeringPress.begin(androidKeyId(event.getKeyCode()), command, longCommand,
                            android.os.SystemClock.elapsedRealtime());
                    uiHandler.postDelayed(steeringLongPressTask, SteeringProtocol.LONG_PRESS_MS);
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!steeringPress.isPressed(androidKeyId(event.getKeyCode()))) {
                    return super.dispatchKeyEvent(event);
                }
                uiHandler.removeCallbacks(steeringLongPressTask);
                boolean longFired = steeringPress.isLongFired();
                long held = steeringPress.heldMs(android.os.SystemClock.elapsedRealtime());
                int action = steeringPress.finish(android.os.SystemClock.elapsedRealtime());
                if (longFired) {
                    android.util.Log.i("AutoPlayKeys", "dispatch held=" + held
                            + "ms already fired long press, skip single");
                    return true;
                }
                android.util.Log.i("AutoPlayKeys", "dispatch held=" + held + "ms single=" + action);
                if (action >= 0 && connectionService != null) {
                    connectionService.sendSteeringCommand(action, "android");
                }
                return true;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    void onNightModeChanged() {
        refreshConnectionSettings();
    }

    void onMicGainChanged(int decibels) {
        refreshConnectionSettings();
    }

    void onOutputGainChanged(int percent) {
        refreshConnectionSettings();
    }

    void onNavigationGainChanged(int percent) {
        refreshConnectionSettings();
    }

    void onTouchEnabledChanged(boolean enabled) {
        touchEnabled = enabled;
    }

    void onKeepScreenOnChanged(boolean enabled) {
        applyKeepScreenOn(enabled);
    }

    void onAspectChanged() {
        layoutVideo();
    }

    void onFullscreenChanged() {
        if (projectionMode) {
            applyImmersive(isFullscreenApp());
            updateRootPadding();
            layoutVideo();
        }
    }

    void restoreDefaults() {
        getPreferences(MODE_PRIVATE).edit().clear().apply();
        cancelBootNotification();
        touchEnabled = true;
        applyKeepScreenOn(true);
        if (connectionService != null) {
            connectionService.refreshSettings();
            connectionService.updateBackgroundPolicy();
            connectionService.syncCarLogo();
        }
        videoRenderer.setDecoderMode(VideoOptions.HARDWARE);
        videoRenderer.setFrameRate(VideoPreferences.frameRate(getPreferences(MODE_PRIVATE)));
        int[] size = getRequestedVideoSize();
        if (connectionService != null) connectionService.updateVideoSettings(size[0], size[1]);
        onNavigationBarChanged();
        selectedCarLogo = findCarLogo(CarLogoUpload.DEFAULT_PATH);
        refreshTheme();
    }

    SettingsScreens.CheckResult checkMicrophone() {
        if (!AndroidCompat.hasMicrophone(this)) {
            return new SettingsScreens.CheckResult(false, "未授予麦克风权限");
        }
        int minBuffer = android.media.AudioRecord.getMinBufferSize(16000,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
        return minBuffer > 0
                ? new SettingsScreens.CheckResult(true, "麦克风可用")
                : new SettingsScreens.CheckResult(false, "无法使用麦克风");
    }

    SettingsScreens.CheckResult checkUsb() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isTarget(device)) {
                return usbManager.hasPermission(device)
                        ? new SettingsScreens.CheckResult(true, "已识别车机盒")
                        : new SettingsScreens.CheckResult(false, "需要 USB 授权");
            }
        }
        return new SettingsScreens.CheckResult(false, "请插入USB设备");
    }

    void showCarLogoPicker() {
        if (carLogoOptions == null || carLogoOptions.isEmpty()) {
            Toast.makeText(this, "未能读取汽车图标列表", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择汽车图标")
                .setAdapter(new CarLogoAdapter(), (dialog, position) -> {
                    CarLogoOption selected = carLogoOptions.get(position);
                    selectedCarLogo = selected;
                    getPreferences(MODE_PRIVATE).edit()
                            .putString("CarPlayOEMIconPath", selected.path)
                            .putString("CarPlayOEMIconName", selected.label)
                            .apply();
                    updateCarLogoViews();
                    if (connectionService != null) connectionService.syncCarLogo();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private List<CarLogoOption> loadCarLogoOptions() {
        List<CarLogoOption> options = new ArrayList<>();
        try {
            JSONArray logos = new JSONArray(readAssetText("carlogo.json"));
            for (int index = 0; index < logos.length(); index++) {
                JSONObject logo = logos.getJSONObject(index);
                String name = logo.optString("name", "");
                int brandId = logo.optInt("brandID", 0);
                String path = logo.optString("path", "");
                String label = logo.optString("zh-CN", name);
                if (path.isEmpty() || name.equalsIgnoreCase("addCustomLogo")) {
                    continue;
                }
                options.add(new CarLogoOption(name, label, path, brandId));
            }
        } catch (IOException | JSONException exception) {
            android.util.Log.e("AutoPlay", "Failed to load car logo list", exception);
        }
        return options;
    }

    private String readAssetText(String assetPath) throws IOException {
        return new String(readAssetBytes(assetPath), StandardCharsets.UTF_8);
    }

    private byte[] readAssetBytes(String assetPath) throws IOException {
        try (InputStream input = getAssets().open(assetPath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private Bitmap loadCarLogoBitmap(CarLogoOption option) {
        if (option == null) {
            return null;
        }
        if (option.bitmap == null) {
            try (InputStream input = getAssets().open(option.path)) {
                option.bitmap = BitmapFactory.decodeStream(input);
            } catch (IOException exception) {
                android.util.Log.e("AutoPlay", "Failed to load car logo " + option.path, exception);
            }
        }
        return option.bitmap;
    }

    private CarLogoOption findCarLogo(String path) {
        if (carLogoOptions == null) {
            return null;
        }
        for (CarLogoOption option : carLogoOptions) {
            if (option.path.equals(path)) {
                return option;
            }
        }
        return null;
    }

    private void updateCarLogoViews() {
        if (screens != null) {
            screens.refreshCarLogo();
        }
        updateStage();
    }

    private final class CarLogoAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return carLogoOptions.size();
        }

        @Override
        public CarLogoOption getItem(int position) {
            return carLogoOptions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CarLogoOption option = getItem(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));

            ImageView icon = new ImageView(MainActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageBitmap(loadCarLogoBitmap(option));
            row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

            boolean selected = option == selectedCarLogo;
            TextView name = Ui.text(MainActivity.this, option.label, 16, Ui.TEXT, selected);
            name.setPadding(dp(16), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            if (selected) {
                row.addView(Ui.text(MainActivity.this, "✓", 18, Ui.ACCENT, true));
            }
            return row;
        }
    }

    private static final class CarLogoOption {
        final String name;
        final String label;
        final String path;
        final int brandId;
        Bitmap bitmap;

        CarLogoOption(String name, String label, String path, int brandId) {
            this.name = name;
            this.label = label;
            this.path = path;
            this.brandId = brandId;
        }
    }

    private int[] getRequestedVideoSize() {
        String selected = getPreferences(MODE_PRIVATE).getString("video_size", null);
        if (selected != null) return orientVideoSize(VideoOptions.parseSize(selected));
        int resolution = getPreferences(MODE_PRIVATE).getInt("resolution", 0);
        if (resolution == 1) {
            return orientVideoSize(new int[]{1280, 720});
        }
        if (resolution == 2) {
            return orientVideoSize(new int[]{1920, 1080});
        }
        return physicalVideoSize();
    }

    private int[] physicalVideoSize() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{metrics.widthPixels & ~1, metrics.heightPixels & ~1};
    }

    private int[] orientVideoSize(int[] size) {
        int[] screen = physicalVideoSize();
        if ((screen[0] > screen[1]) != (size[0] > size[1])) {
            return new int[]{size[1], size[0]};
        }
        return size;
    }

    private void applyKeepScreenOn(boolean enabled) {
        if (enabled) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void applyImmersive(boolean enabled) {
        int mask = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(enabled ? flags | mask : flags & ~mask);
        if (!enabled) {
            onNavigationBarChanged();
        }
    }

    private void setProjectionMode(boolean enabled) {
        if (rootPage == null || videoRenderer == null || projectionMode == enabled) {
            return;
        }
        projectionMode = enabled;
        for (int index = 0; index < rootPage.getChildCount(); index++) {
            View child = rootPage.getChildAt(index);
            boolean isVideo = child == videoRenderer;
            child.setVisibility(isVideo == enabled ? View.VISIBLE : View.GONE);
        }
        if (enabled) {
            rootPage.setBackgroundColor(0xFF000000);
        } else {
            rootPage.setBackground(Ui.pageBackground());
        }
        applyImmersive(enabled && isFullscreenApp());
        updateRootPadding();
        layoutVideo();
        if (!enabled && themeRefreshPending) {
            themeRefreshPending = false;
            refreshTheme();
        }
    }

    private void layoutVideo() {
        if (rootPage == null || videoRenderer == null) {
            return;
        }
        LinearLayout.LayoutParams existing = (LinearLayout.LayoutParams) videoRenderer.getLayoutParams();
        LinearLayout.LayoutParams video = new LinearLayout.LayoutParams(existing);
        int availableWidth = rootPage.getWidth() - rootPage.getPaddingLeft() - rootPage.getPaddingRight();
        int availableHeight = rootPage.getHeight() - rootPage.getPaddingTop() - rootPage.getPaddingBottom();
        boolean differentOrientation = (availableWidth > availableHeight) != (lastVideoWidth > lastVideoHeight);
        boolean original = (getPreferences(MODE_PRIVATE).getInt("video_scale_mode", 1) == 0
                || differentOrientation) && lastVideoWidth > 0 && lastVideoHeight > 0;
        video.gravity = android.view.Gravity.NO_GRAVITY;
        rootPage.setGravity(android.view.Gravity.TOP);
        if (!projectionMode) {
            video.width = LinearLayout.LayoutParams.MATCH_PARENT;
            video.height = 0;
            video.weight = 0;
        } else if (original) {
            if (availableWidth <= 0 || availableHeight <= 0) {
                rootPage.post(this::layoutVideo);
            } else {
                int[] fitted = VideoGeometry.fit(availableWidth, availableHeight,
                        lastVideoWidth, lastVideoHeight);
                video.width = fitted[0];
                video.height = fitted[1];
                video.weight = 0;
                video.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                rootPage.setGravity(android.view.Gravity.CENTER_VERTICAL);
            }
        } else {
            video.width = LinearLayout.LayoutParams.MATCH_PARENT;
            video.height = 0;
            video.weight = 1.0f;
        }
        // Layout callbacks can re-enter here; do not request another layout if nothing changed.
        if (existing.width != video.width || existing.height != video.height
                || existing.weight != video.weight || existing.gravity != video.gravity) {
            videoRenderer.setLayoutParams(video);
        }
    }

    private boolean isTarget(UsbDevice device) {
        return ConnectionService.isTarget(device);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
