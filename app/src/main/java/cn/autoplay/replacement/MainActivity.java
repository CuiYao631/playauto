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
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 701;
    private static final int REQUEST_ENV_MICROPHONE = 702;
    private static final int REQUEST_BOOT_NOTIFICATIONS = 703;
    private static final int REQUEST_CONNECTION_NOTIFICATIONS = 704;
    private static final int PAGE_SETTINGS = 0;
    private static final int PAGE_MORE = 1;
    private static final int PAGE_ENVIRONMENT = 2;
    private static final int PAGE_STEERING = 3;
    private static final int PAGE_ADVANCED = 4;
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
            boolean press = vendorKeys.press(key, phase, android.os.SystemClock.elapsedRealtime());
            if (learningPage && screens.handleVendorKey(key, phase, press)) return;
            int command = SteeringProtocol.vendorCommand(getPreferences(MODE_PRIVATE), key);
            if (press && command >= 0 && phonePlugged && connectionService != null) {
                connectionService.sendSteeringCommand(command, "vendor");
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
    private boolean requestingMicrophone;
    private final ConnectionService.Client connectionClient = new ConnectionService.Client() {
        @Override public void onConnectionState() {
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
            if (!phonePlugged) {
                videoStarted = false;
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
                setProjectionMode(true);
            }
            videoRenderer.setVisibility(View.VISIBLE);
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
            .getString("CarPlayOEMIconPath", "CarLogo/default.png"));
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
                runOnUiThread(() -> connectionStatus.setText("盒子：H.264 解码失败：" + error));
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
        videoRenderer.setTextureRendering(getPreferences(MODE_PRIVATE).getInt("render_mode", 0) == 1);
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
        if (box == Ui.STATE_ERROR) {
            prompt = "设备连接失败，请重新插拔设备";
            dotColor = Ui.ERROR;
        } else if (box == Ui.STATE_IDLE) {
            prompt = "请将设备插入车机的USB口";
        } else if (box == Ui.STATE_WAIT) {
            prompt = "连接中，请稍等...";
            dotColor = Ui.WARNING;
            connecting = true;
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
        new AlertDialog.Builder(this)
                .setTitle("连接详情")
                .setMessage("盒子：" + connectionStateLabel(connectionStatus.getState())
                        + "\n\n手机：" + connectionStateLabel(phoneStatus.getState()))
                .setPositiveButton("知道了", null)
                .show();
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

    String advancedUsbStatus() {
        return connectionService == null ? "连接服务尚未就绪"
                : connectionService.usbDiagnostics();
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
        videoRenderer.setTextureRendering(getPreferences(MODE_PRIVATE).getInt("render_mode", 0) == 1);
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

    private void cancelBootNotification() {
        android.app.NotificationManager manager = (android.app.NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(BootStartReceiver.NOTIFICATION_ID);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int command = connectionService == null ? -1 : connectionService.mediaCommand(event.getKeyCode());
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
        if (command >= 0 && phonePlugged && protocolSession != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    return connectionService != null
                            && connectionService.sendSteeringCommand(command, "android");
                }
                return true;
            }
            return event.getAction() == KeyEvent.ACTION_UP || super.dispatchKeyEvent(event);
        }
        return super.dispatchKeyEvent(event);
    }

    void onConnectModeChanged() {
        refreshConnectionSettings();
    }

    void onNightModeChanged() {
        refreshConnectionSettings();
    }

    void onMicSourceChanged() {
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
        videoRenderer.setTextureRendering(false);
        int[] size = getRequestedVideoSize();
        if (connectionService != null) connectionService.updateVideoSettings(size[0], size[1]);
        onNavigationBarChanged();
        selectedCarLogo = carLogoOptions.isEmpty() ? null : carLogoOptions.get(0);
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
