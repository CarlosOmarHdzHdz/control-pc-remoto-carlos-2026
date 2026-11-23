package com.proyector.receiver;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.proyector.shared.Wire;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
    private int BG, HEADER, PANEL, WELL, BORDER, TEXT, MUTED, ACCENT, ACCENT_SURFACE, WARNING, DANGER, ON_ACCENT;
    private boolean dark;
    private int appearance;
    private static final String[] THEMES = {"Sistema", "Blanco", "Oscuro"};
    private static final int INK = 0xFF291D3C;
    private static final int PEACH = 0xFFFFB295;
    private static final int ON_INK = 0xFFFFF6EC;
    private static final int HOME = 0, REMOTE = 1, MONITOR = 2, CONTROLS = 3, CONNECTION = 4;
    private static final String[] TAB_NAMES = {"Inicio", "Remoto", "Monitor", "Controles", "Conexión"};
    private static final String[] TAB_ICONS = {"home", "screen", "dual", "sliders", "link"};

    private enum Profile {
        SAVER("Ahorro", 1, 1, true),
        BALANCED("Equilibrado", 2, 2, true),
        SMOOTH("Fluido", 2, 4, true),
        FULL_HD("Full HD", 3, 2, false);

        final String label;
        final int quality, fps;
        final boolean adaptive;

        Profile(String label, int quality, int fps, boolean adaptive) {
            this.label = label;
            this.quality = quality;
            this.fps = fps;
            this.adaptive = adaptive;
        }

        String limits() { return Wire.RESOLUTIONS[quality] + " · " + Wire.FRAME_RATES[fps] + " FPS"; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScreenCapture capture = new ScreenCapture();
    private final AtomicReference<VideoFrame> latestFrame = new AtomicReference<>();
    private final AtomicBoolean drawScheduled = new AtomicBoolean();
    private final List<View> powerButtons = new ArrayList<>();
    private final List<View> audioButtons = new ArrayList<>();
    private final List<View> shortcutButtons = new ArrayList<>();
    private final List<View> profileTiles = new ArrayList<>();
    private final String[] routeAddresses = {"", "", ""};
    private final String[] routePorts = {"5050", "5050", "5050"};
    private final Runnable accessibleVolume = this::applyVolumeFromAccessibility;
    private SharedPreferences preferences;
    private ReceiverLink link;
    private ReceiverLink.State state = ReceiverLink.State.IDLE;
    private ReceiverLink.Mode mode = ReceiverLink.Mode.LAN;
    private ReceiverLink.Mode linkedMode;
    private List<ReceiverLink.Endpoint> offers = Collections.emptyList();
    private String fingerprint;
    private String computer = "";
    private String status = "Abre el emisor en Windows para conectar.";
    private String command = "";
    private String secondary = "";
    private String savedIp = "";
    private String savedPin = "";
    private String savedPort = Integer.toString(Wire.PORT);
    private String lastVideo = "";
    private String liveVideo = "";
    private boolean manual;
    private boolean error;
    private boolean commandError;
    private boolean wide;
    private boolean foreground;
    private boolean muted;
    private boolean charging;
    private boolean volumeDragging;
    private int volume = -1, battery = -1, phoneBattery = -1;
    private int quality = 2, frameRate = 2, page = HOME;
    private int pointerSpeed = 150;
    private boolean adaptive = true;
    private long latency = -1;
    private int monitorCount;
    private int viewing = Wire.PAUSED;
    private DesktopView.Mode viewerMode = DesktopView.Mode.DIRECT;
    private int revision;
    private int visibleSequence;
    private boolean gotFrame;
    private long meterStart;
    private int frameCount;
    private FrameLayout root, pageHost, viewerHost;
    private LinearLayout shell, nav, manualFields;
    private View[] pages;
    private Button[] modeButtons;
    private Switch adaptiveSwitch;
    private NavItem[] navigation;
    private DesktopView desktop;
    private OrbitArtwork orbit;
    private Icon pcBatteryIcon, deviceBatteryIcon, speakerIcon;
    private boolean phoneCharging;
    private boolean waitingForMonitor;
    private Button tailscale, pasteRoute;
    private TextView savedRouteHint;
    private LatencyHistory history;
    private Button homeOpen, viewerHandle;
    private LinearLayout padButtons;
    private LinearLayout viewerDock, viewerPanel;
    private TextView viewerSummary;
    private final Button[] quickModes = new Button[DesktopView.Mode.values().length];
    private Button quickKeyboard, quickCapture, quickDisplay;
    private boolean viewerExpanded;
    private TextView homeImageProfile, historyStatus, viewerStats, viewerAudio, viewerNotice;
    private Button viewerLower, viewerRaise, viewerMute;
    private TextView badge, phoneLabel, homeComputer, homeStatus, homeBattery, homeVolume, homeLatency;
    private TextView result, resolutionHint, measurement, profileHint, pointerSpeedLabel, monitorHint, videoHint, volumeLabel, discovery, connectionStatus, routeHint;
    private Button connect, manualToggle, usbSettings, adb, forget, startSecond, mute, qualityButton, rateButton;
    private EditText pin, address, port;
    private SeekBar slider;
    private AlertDialog verifyDialog, viewerDialog, textDialog;
    private WifiManager.MulticastLock broadcasts;
    private final BroadcastReceiver batteryChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { readPhoneBattery(intent); }
    };

    @Override protected void onCreate(Bundle restored) {
        super.onCreate(restored);
        preferences = getSharedPreferences("pc-link", MODE_PRIVATE);
        appearance = bounded(preferences.getInt("appearance", 0), 2);
        fingerprint = preferences.getString("fingerprint", null);
        computer = preferences.getString("computer", "");
        mode = ReceiverLink.Mode.values()[bounded(preferences.getInt("mode", 0), 2)];
        for (ReceiverLink.Mode route : ReceiverLink.Mode.values()) {
            routeAddresses[route.ordinal()] = preferences.getString("ip_" + route.name(), "");
            routePorts[route.ordinal()] = preferences.getString("port_" + route.name(), Integer.toString(Wire.PORT));
        }
        savedIp = routeAddresses[mode.ordinal()];
        savedPort = routePorts[mode.ordinal()];
        quality = bounded(preferences.getInt("quality", 2), Wire.RESOLUTIONS.length - 1);
        frameRate = bounded(preferences.getInt("fps", 2), Wire.FRAME_RATES.length - 1);
        adaptive = preferences.getBoolean("adaptive", true);
        pointerSpeed = bounded(preferences.getInt("pointerSpeed", 150), 300);
        pointerSpeed = Math.max(50, pointerSpeed);
        if (restored != null) page = bounded(restored.getInt("page", HOME), TAB_NAMES.length - 1);
        desktop = new DesktopView(this, new DesktopView.Input() {
            @Override public void send(int command, int... values) { if (gotFrame) link.pointer(command, visibleSequence, values); }
            @Override public void keyboard() { showKeyboard(); }
            @Override public void leftClick(boolean twice) { sendPadClick(twice); }
        });
        desktop.pointerSensitivity(pointerSpeed);
        history = new LatencyHistory(this);
        link = new ReceiverLink(main, new LinkEvents());
        buildUi();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        link.foreground(true);
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = Build.VERSION.SDK_INT >= 33
                ? registerReceiver(batteryChanges, filter, Context.RECEIVER_NOT_EXPORTED)
                : registerReceiver(batteryChanges, filter);
        readPhoneBattery(sticky);
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                broadcasts = wifi.createMulticastLock("control-pc-discovery");
                broadcasts.setReferenceCounted(false);
                broadcasts.acquire();
            }
        } catch (RuntimeException ignored) { broadcasts = null; }
        configureStream();
    }

    @Override protected void onStop() {
        desktop.endGesture();
        main.removeCallbacks(accessibleVolume);
        foreground = false;
        link.foreground(false);
        configureStream();
        unregisterReceiver(batteryChanges);
        if (broadcasts != null && broadcasts.isHeld()) broadcasts.release();
        broadcasts = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (verifyDialog != null) verifyDialog.dismiss();
        dismissViewerDialogs();
        link.close();
        VideoFrame abandoned = latestFrame.getAndSet(null);
        if (abandoned != null) abandoned.image.recycle();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", page);
        super.onSaveInstanceState(out);
    }

    @Override public void onConfigurationChanged(Configuration updated) {
        super.onConfigurationChanged(updated);
        rebuildUi();
    }

    private void rebuildUi() {
        rememberFields();
        desktop.endGesture();
        dismissViewerDialogs();
        if (desktop.getParent() instanceof FrameLayout) ((FrameLayout) desktop.getParent()).removeView(desktop);
        buildUi();
    }

    private void buildUi() {
        Configuration current = getResources().getConfiguration();
        dark = appearance == 2 || (appearance == 0 && (current.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        BG = dark ? 0xFF19151F : 0xFFFDFBFC;
        HEADER = dark ? 0xFF201B26 : Color.WHITE;
        PANEL = dark ? 0xFF29232F : Color.WHITE;
        WELL = dark ? 0xFF342D3C : 0xFFF5F0F4;
        BORDER = dark ? 0xFF524559 : 0xFFE4D8DE;
        TEXT = dark ? 0xFFF8F1F5 : INK;
        MUTED = dark ? 0xFFCCBBC9 : 0xFF665968;
        ACCENT = dark ? 0xFFF2AFC5 : 0xFF833A5C;
        ACCENT_SURFACE = dark ? 0xFF4D3043 : 0xFFF8DFE8;
        ON_ACCENT = dark ? INK : Color.WHITE;
        WARNING = dark ? 0xFFFFBF9F : 0xFF933F26;
        DANGER = dark ? 0xFF593539 : 0xFFF9DFDD;
        history.colors(BORDER, ACCENT);
        wide = current.screenWidthDp >= 700;
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        shell = vertical();
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets system = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
                shell.setPadding(system.left, system.top, system.right, Math.max(keyboard, system.bottom));
                if (!wide) nav.setVisibility(keyboard > system.bottom ? View.GONE : View.VISIBLE);
            } else {
                shell.setPadding(insets.getSystemWindowInsetLeft(), insets.getStableInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            View viewerButton = viewerDock;
            if (viewerButton != null) {
                FrameLayout.LayoutParams placement = (FrameLayout.LayoutParams) viewerButton.getLayoutParams();
                android.view.DisplayCutout cutout = Build.VERSION.SDK_INT >= 28 ? insets.getDisplayCutout() : null;
                int right = cutout == null ? 0 : cutout.getSafeInsetRight();
                int left = cutout == null ? 0 : cutout.getSafeInsetLeft();
                int top = dp(10) + (cutout == null ? 0 : cutout.getSafeInsetTop());
                int width = Math.min(dp(372), getResources().getDisplayMetrics().widthPixels - left - right - dp(22));
                if (placement.width != width || placement.topMargin != top || placement.rightMargin != right + dp(10) || placement.leftMargin != left + dp(10)) {
                    placement.width = width;
                    placement.setMargins(left + dp(10), top, right + dp(10), 0);
                    viewerButton.setLayoutParams(placement);
                }
            }
            return insets;
        });

        LinearLayout head = horizontal();
        head.setBackgroundColor(HEADER);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(8), dp(15), dp(8));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.proyector_icon);
        head.addView(mark, lp(dp(38), dp(38)));
        TextView brand = editorial("Orbivía", 25, TEXT);
        brand.setSingleLine();
        LinearLayout.LayoutParams brandParams = weighted(1f);
        brandParams.setMarginStart(dp(10));
        head.addView(brand, brandParams);
        phoneLabel = text("", 11, MUTED, true);
        phoneLabel.setPadding(dp(6), 0, dp(8), 0);
        if (wide) head.addView(devicePower());
        badge = pill("Sin conexión", MUTED, WELL);
        badge.setOnClickListener(view -> showPage(CONNECTION, true));
        head.addView(badge);
        Button appearanceButton = button("", false);
        appearanceButton.setPadding(0, 0, 0, 0);
        appearanceButton.setContentDescription("Apariencia · " + THEMES[appearance]);
        appearanceButton.setOnClickListener(view -> chooseTheme());
        FrameLayout themeSlot = new FrameLayout(this);
        themeSlot.addView(appearanceButton, new FrameLayout.LayoutParams(-1, -1));
        themeSlot.addView(new Icon(this, "theme", ACCENT), new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));
        LinearLayout.LayoutParams styleSize = lp(dp(48), dp(48));
        styleSize.setMarginStart(dp(7));
        head.addView(themeSlot, styleSize);
        shell.addView(head, lp(-1, -2));

        LinearLayout body = wide ? horizontal() : vertical();
        shell.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        nav = wide ? vertical() : horizontal();
        nav.setPadding(dp(8), dp(7), dp(8), dp(7));
        nav.setBackground(surface(HEADER, wide ? 0 : 22, BORDER));
        navigation = new NavItem[TAB_NAMES.length];
        for (int i = 0; i < navigation.length; i++) {
            final int destination = i;
            navigation[i] = new NavItem(TAB_NAMES[i], TAB_ICONS[i]);
            navigation[i].setOnClickListener(view -> showPage(destination, true));
            if (wide) add(nav, navigation[i], 3);
            else nav.addView(navigation[i], weighted(1f));
        }
        if (wide) body.addView(nav, lp(dp(130), -1));
        pageHost = new FrameLayout(this);
        if (wide) body.addView(pageHost, new LinearLayout.LayoutParams(0, -1, 1));
        else {
            body.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout.LayoutParams dock = lp(-1, -2);
            dock.setMargins(dp(10), dp(4), dp(10), dp(6));
            body.addView(nav, dock);
        }

        pages = new View[]{homePage(), remotePage(), monitorPage(), controlsPage(), connectionPage()};
        for (View item : pages) {
            item.setVisibility(View.GONE);
            pageHost.addView(item, new FrameLayout.LayoutParams(-1, -1));
        }
        viewerHost = new FrameLayout(this);
        viewerHost.setBackgroundColor(Color.BLACK);
        viewerHost.addView(desktop, new FrameLayout.LayoutParams(-1, -1));
        videoHint = text("Preparando imagen…", 15, ON_INK, true);
        videoHint.setGravity(Gravity.CENTER);
        viewerHost.addView(videoHint, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        viewerDock = buildViewerDock();
        FrameLayout.LayoutParams handlePosition = new FrameLayout.LayoutParams(
                Math.min(dp(372), getResources().getDisplayMetrics().widthPixels - dp(22)),
                -2, Gravity.TOP | Gravity.END);
        handlePosition.setMargins(dp(10), dp(10), dp(10), 0);
        viewerHost.addView(viewerDock, handlePosition);
        padButtons = horizontal();
        padButtons.setGravity(Gravity.CENTER);
        padButtons.setPadding(dp(5), dp(5), dp(5), dp(5));
        padButtons.setBackground(surface(0xF22B2136, 18, 0xFF73566A));
        addSegment(padButtons, viewerAction("Clic", () -> sendPadClick(false)));
        addSegment(padButtons, viewerAction("Doble clic", () -> sendPadClick(true)));
        addSegment(padButtons, viewerAction("Derecho", () -> {
            if (padReady()) link.pointer(Wire.RIGHT_CLICK, visibleSequence);
        }));
        FrameLayout.LayoutParams clicks = new FrameLayout.LayoutParams(
                Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(24)), -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clicks.bottomMargin = dp(16);
        viewerHost.addView(padButtons, clicks);
        root.addView(viewerHost, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        showPage(page, false);
        setViewerVisibility();
        refresh();
        if (page == HOME) orbit.post(orbit::enter);
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    private void chooseTheme() {
        dialog().setTitle("Apariencia").setSingleChoiceItems(THEMES, appearance, (picker, choice) -> {
            picker.dismiss();
            if (choice == appearance) return;
            appearance = choice;
            preferences.edit().putInt("appearance", choice).apply();
            rebuildUi();
        }).setNegativeButton("Cerrar", null).show();
    }

    private View homePage() {
        LinearLayout content = page(null);
        LinearLayout overview = vertical();
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(gradientSurface(new int[]{INK, 0xFF432A54}, 24, 0));
        orbit = new OrbitArtwork(this);
        hero.addView(orbit, new FrameLayout.LayoutParams(dp(wide ? 212 : 175), dp(155), Gravity.TOP | Gravity.END));
        LinearLayout device = vertical();
        device.setPadding(dp(19), dp(16), dp(19), dp(18));
        if (wide) {
            Icon station = new Icon(this, "screen", PEACH);
            device.addView(station, lp(dp(32), dp(32)));
        } else {
            phoneLabel = text("", 12, ON_INK, true);
            phoneLabel.setPadding(dp(5), 0, 0, 0);
            View power = devicePower();
            deviceBatteryIcon.tint(PEACH);
            device.addView(power);
        }
        homeComputer = editorial("Tu computadora", 30, ON_INK);
        homeComputer.setMaxLines(2);
        homeComputer.setEllipsize(TextUtils.TruncateAt.END);
        add(device, homeComputer, 12);
        homeStatus = text("", 13, 0xFFE5CDD8, false);
        add(device, homeStatus, 5);
        homeOpen = button("Vincular PC", true);
        homeOpen.setTextColor(INK);
        homeOpen.setBackground(tappable(PEACH, 12, 0));
        homeOpen.setOnClickListener(view -> {
            if (state == ReceiverLink.State.CONNECTED) openViewer(Wire.DESKTOP, DesktopView.Mode.DIRECT);
            else showPage(CONNECTION, true);
        });
        add(device, homeOpen, 16);
        hero.addView(device, new FrameLayout.LayoutParams(-1, -2));
        overview.addView(hero, lp(-1, -2));

        LinearLayout metrics = horizontal();
        metrics.setBaselineAligned(false);
        metrics.setBackground(surface(PANEL, 16, BORDER));
        homeBattery = title("—", 18);
        homeVolume = title("—", 18);
        homeLatency = title("—", 18);
        metrics.addView(metricCard("battery", "Batería PC", homeBattery), weighted(1f));
        metrics.addView(metricCard("volume", "Volumen", homeVolume), weighted(1f));
        metrics.addView(metricCard("latency", "Respuesta", homeLatency), weighted(1f));
        add(overview, metrics, 10);

        LinearLayout linkHealth = card(false);
        linkHealth.addView(title("Respuesta de la conexión", 15));
        historyStatus = text("", 12, MUTED, false);
        add(linkHealth, historyStatus, 3);
        if (history.getParent() instanceof LinearLayout) ((LinearLayout) history.getParent()).removeView(history);
        LinearLayout.LayoutParams graph = lp(-1, dp(40));
        graph.topMargin = dp(5);
        linkHealth.addView(history, graph);

        LinearLayout actions = vertical();
        actions.addView(editorial("Accesos rápidos", 24, TEXT));
        add(actions, tilePair(featureTile("touch", "Panel táctil", "Mover el cursor", false,
                        () -> openViewer(Wire.DESKTOP, DesktopView.Mode.TOUCHPAD)),
                featureTile("dual", "Segundo monitor", "Extender Windows", true, () -> showPage(MONITOR, true))), 9);
        add(actions, actionRow("eye", "Solo ver", "Amplía la imagen con dos dedos", () -> openViewer(Wire.DESKTOP, DesktopView.Mode.VIEW_ONLY)), 8);
        add(actions, actionRow("sliders", "Controles", "Volumen, multimedia y energía", () -> showPage(CONTROLS, true)), 6);
        LinearLayout imageProfile = card(true);
        imageProfile.addView(title("Calidad de imagen  ›", 15));
        homeImageProfile = text("", 12, MUTED, false);
        add(imageProfile, homeImageProfile, 3);
        imageProfile.setFocusable(true);
        imageProfile.setOnClickListener(view -> showPage(REMOTE, true));
        pressMotion(imageProfile);
        add(actions, imageProfile, 8);
        if (wide) add(overview, linkHealth, 10);
        add(content, columns(overview, actions), 0);
        if (!wide) add(content, linkHealth, 13);
        return scroll(content);
    }

    private View remotePage() {
        LinearLayout content = page("Escritorio remoto");
        profileTiles.clear();
        add(content, tilePair(actionRow("screen", "Control directo", "Toca para usar Windows", () -> openViewer(Wire.DESKTOP, DesktopView.Mode.DIRECT)),
                actionRow("touch", "Panel táctil", "Desliza para mover el cursor", () -> openViewer(Wire.DESKTOP, DesktopView.Mode.TOUCHPAD))), 13);
        add(content, actionRow("eye", "Solo ver", "Observa y amplía la pantalla sin enviar toques", () -> openViewer(Wire.DESKTOP, DesktopView.Mode.VIEW_ONLY)), 7);
        LinearLayout video = card(false);
        video.addView(title("Calidad de imagen", 17));
        adaptiveSwitch = new Switch(this);
        adaptiveSwitch.setText("Ajuste automático");
        adaptiveSwitch.setTextColor(TEXT);
        adaptiveSwitch.setMinHeight(dp(47));
        adaptiveSwitch.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACCENT, MUTED}));
        adaptiveSwitch.setChecked(adaptive);
        adaptiveSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (adaptive == enabled) return;
            adaptive = enabled;
            saveVideoSettings();
        });
        add(video, adaptiveSwitch, 4);
        qualityButton = button("", false);
        qualityButton.setOnClickListener(view -> dialog().setTitle("Resolución máxima")
                .setSingleChoiceItems(Wire.RESOLUTIONS, quality, (dialog, selected) -> {
                    quality = selected;
                    saveVideoSettings();
                    dialog.dismiss();
                }).setNegativeButton("Cancelar", null).show());
        rateButton = button("", false);
        rateButton.setOnClickListener(view -> dialog().setTitle("Fluidez máxima")
                .setSingleChoiceItems(new String[]{"12 FPS", "20 FPS", "30 FPS", "45 FPS", "60 FPS"}, frameRate, (dialog, selected) -> {
                    frameRate = selected;
                    saveVideoSettings();
                    dialog.dismiss();
                }).setNegativeButton("Cancelar", null).show());
        add(video, tilePair(qualityButton, rateButton), 5);
        resolutionHint = text("", 13, MUTED, false);
        add(video, resolutionHint, 8);
        measurement = text("", 12, ACCENT, false);
        add(video, measurement, 6);

        LinearLayout profiles = card(false);
        profiles.addView(title("Perfiles de imagen", 17));
        add(profiles, text("Cambia resolución y fluidez juntos. El ajuste automático se adapta a la red.", 13, MUTED, false), 5);
        profileHint = text("", 12, ACCENT, true);
        add(profiles, profileHint, 8);
        add(profiles, tilePair(profileButton(Profile.SAVER), profileButton(Profile.BALANCED)), 8);
        add(profiles, tilePair(profileButton(Profile.SMOOTH), profileButton(Profile.FULL_HD)), 8);

        LinearLayout pointer = card(false);
        pointer.addView(title("Sensibilidad del panel táctil", 17));
        pointerSpeedLabel = text(pointerSpeed + " %", 14, ACCENT, true);
        add(pointer, pointerSpeedLabel, 7);
        SeekBar speed = new SeekBar(this);
        speed.setMax(250);
        speed.setProgress(pointerSpeed - 50);
        speed.setProgressTintList(ColorStateList.valueOf(ACCENT));
        speed.setThumbTintList(ColorStateList.valueOf(ACCENT));
        speed.setContentDescription("Sensibilidad del panel táctil, del 50 al 300 por ciento");
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (!user) return;
                pointerSpeed = value + 50;
                pointerSpeedLabel.setText(pointerSpeed + " %");
                desktop.pointerSensitivity(pointerSpeed);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                preferences.edit().putInt("pointerSpeed", pointerSpeed).apply();
            }
        });
        pointer.addView(speed, lp(-1, dp(42)));
        add(pointer, text("Ajusta cuánto se mueve el cursor con cada deslizamiento.", 12, MUTED, false), 2);

        LinearLayout gestures = card(false);
        gestures.addView(title("Gestos en pantalla completa", 17));
        add(gestures, text("Dos dedos  ·  Desplazar", 14, MUTED, false), 8);
        add(gestures, text("Mantener pulsado  ·  Clic derecho", 14, MUTED, false), 7);
        add(gestures, text("Tres dedos  ·  Abrir teclado", 14, MUTED, false), 7);
        add(gestures, text("Panel táctil: toca dos veces y mantén para arrastrar.", 13, MUTED, false), 9);
        add(content, columns(video, profiles), 13);
        add(content, columns(pointer, gestures), 10);
        return scroll(content);
    }

    private View monitorPage() {
        LinearLayout content = page("Segundo monitor");
        LinearLayout card = card(false);
        card.addView(iconBadge("dual"), lp(dp(47), dp(47)));
        add(card, title("Pantalla adicional de Windows", 19), 10);
        monitorHint = text("", 14, MUTED, false);
        add(card, monitorHint, 6);
        startSecond = button("Abrir a pantalla completa", true);
        startSecond.setOnClickListener(view -> monitorAction());
        add(card, startSecond, 12);
        LinearLayout help = card(false);
        help.addView(title("Preparar el escritorio extendido", 17));
        add(help, text("En Orbivía para Windows, abre Pantallas. Si tienes una sola pantalla, pulsa Preparar monitor virtual y elige Install en Virtual Driver Control.", 14, MUTED, false), 8);
        add(help, text("Pulsa Extender escritorio en la PC. Cuando aparezca la pantalla adicional, podrás mover ventanas hacia ella con Win + Mayús + Flecha.", 14, MUTED, false), 8);
        add(content, columns(card, help), 13);
        return scroll(content);
    }

    private void monitorAction() {
        if (state != ReceiverLink.State.CONNECTED) {
            showPage(CONNECTION, true);
        } else if (!secondary.isEmpty()) {
            openViewer(Wire.SECOND_DISPLAY, DesktopView.Mode.DIRECT);
        } else {
            waitingForMonitor = !waitingForMonitor;
            refresh();
        }
    }

    private View controlsPage() {
        powerButtons.clear();
        audioButtons.clear();
        shortcutButtons.clear();
        LinearLayout content = page("Controles de la PC");
        result = text("", 13, MUTED, true);
        result.setPadding(dp(13), dp(9), dp(13), dp(9));
        result.setBackground(surface(WELL, 10, 0));
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, result, 8);
        LinearLayout audio = card(false);
        audio.addView(editorial("Sonido", 24, TEXT));
        volumeLabel = editorial("—", 34, TEXT);
        add(audio, volumeLabel, 5);
        slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        slider.setContentDescription("Nivel de volumen de Windows");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar control, int value, boolean user) {
                if (!user) return;
                volumeLabel.setText(value + " %");
                if (!volumeDragging) {
                    main.removeCallbacks(accessibleVolume);
                    main.postDelayed(accessibleVolume, 220);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar control) { main.removeCallbacks(accessibleVolume); volumeDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar control) {
                volumeDragging = false;
                execute(Wire.VOLUME_SET, control.getProgress());
            }
        });
        audio.addView(slider, lp(-1, dp(43)));
        Button down = control("−5 %", Wire.VOLUME_DOWN, null, audioButtons);
        down.setContentDescription("Bajar volumen cinco por ciento");
        Button up = control("+5 %", Wire.VOLUME_UP, null, audioButtons);
        up.setContentDescription("Subir volumen cinco por ciento");
        add(audio, tilePair(down, up), 5);
        mute = control("Silenciar", Wire.MUTE, null, audioButtons);
        add(audio, mute, 8);
        add(audio, title("Reproducción", 15), 15);
        LinearLayout playback = horizontal();
        addSegment(playback, shortcut("Anterior", 5));
        addSegment(playback, shortcut("Pausa / Play", 4));
        addSegment(playback, shortcut("Siguiente", 6));
        add(audio, playback, 6);

        LinearLayout power = card(false);
        power.addView(editorial("Energía y sesión", 22, TEXT));
        add(power, tilePair(control("Bloquear", Wire.LOCK, null, powerButtons),
                control("Suspender", Wire.SLEEP, "Windows quedará en reposo y la conexión se interrumpirá.", powerButtons)), 10);
        add(power, tilePair(control("Reiniciar", Wire.RESTART, "Guarda el trabajo abierto antes de reiniciar.", powerButtons),
                control("Apagar PC", Wire.SHUTDOWN, "Guarda el trabajo abierto antes de apagar.", powerButtons)), 8);
        LinearLayout shortcuts = card(false);
        shortcuts.addView(editorial("Atajos de Windows", 22, TEXT));
        add(shortcuts, tilePair(shortcut("Escritorio", 1), shortcut("Cambiar ventana", 2)), 8);
        add(shortcuts, shortcut("Administrador de tareas", 3), 7);
        LinearLayout system = vertical();
        system.addView(shortcuts, lp(-1, -2));
        add(system, power, 10);
        add(content, columns(audio, system), 10);
        return scroll(content);
    }

    private View connectionPage() {
        LinearLayout content = page("Conexión");
        LinearLayout card = card(false);
        LinearLayout modes = horizontal();
        modeButtons = new Button[3];
        String[] names = {"Wi-Fi / LAN", "Cable USB", "Otra red"};
        for (int i = 0; i < names.length; i++) {
            ReceiverLink.Mode selection = ReceiverLink.Mode.values()[i];
            modeButtons[i] = button(names[i], false);
            modeButtons[i].setTextSize(13);
            decorate(modeButtons[i], new String[]{"wifi", "usb", "globe"}[i], true);
            modeButtons[i].setOnClickListener(view -> selectMode(selection));
            LinearLayout.LayoutParams p = weighted(1f);
            if (i > 0) p.setMarginStart(dp(5));
            modes.addView(modeButtons[i], p);
        }
        card.addView(modes, lp(-1, -2));
        routeHint = text("", 14, MUTED, false);
        add(card, routeHint, 12);
        usbSettings = button("Abrir ajustes de USB", false);
        usbSettings.setOnClickListener(view -> openUsbSettings());
        add(card, usbSettings, 8);
        tailscale = button("Abrir Tailscale", false);
        tailscale.setOnClickListener(view -> openTailscale());
        add(card, tailscale, 6);
        savedRouteHint = text("", 13, ACCENT, true);
        add(card, savedRouteHint, 8);
        pasteRoute = button("Pegar dirección de la PC", false);
        pasteRoute.setOnClickListener(view -> pasteRoute());
        add(card, pasteRoute, 6);
        discovery = text("", 13, ACCENT, true);
        add(card, discovery, 10);
        pin = field("Código de 6 dígitos", InputType.TYPE_CLASS_NUMBER);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        pin.setText(savedPin);
        pin.setTextSize(19);
        pin.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        pin.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO) { connectOrDisconnect(); return true; }
            return false;
        });
        add(card, pin, 9);

        manualFields = vertical();
        address = field("Dirección IP de Windows", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(savedIp);
        address.setFilters(new InputFilter[]{new InputFilter.LengthFilter(15)});
        port = field("Puerto", InputType.TYPE_CLASS_NUMBER);
        port.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        port.setText(savedPort);
        LinearLayout ipRow = horizontal();
        ipRow.addView(address, weighted(1f));
        LinearLayout.LayoutParams portParams = lp(dp(76), -2);
        portParams.setMarginStart(dp(7));
        ipRow.addView(port, portParams);
        manualFields.addView(ipRow, lp(-1, -2));
        add(card, manualFields, 9);

        connect = button("Conectar", true);
        connect.setOnClickListener(view -> connectOrDisconnect());
        add(card, connect, 11);
        manualToggle = button("Introducir IP manual", false);
        manualToggle.setOnClickListener(view -> { manual = !manual; refreshConnection(); });
        add(card, manualToggle, 7);
        adb = button("Usar USB directo (ADB)", false);
        adb.setOnClickListener(view -> {
            manual = true;
            address.setText("127.0.0.1");
            port.setText(Integer.toString(Wire.PORT));
            refreshConnection();
            dialog().setTitle("USB directo con ADB")
                    .setMessage("En Orbivía para Windows abre Red y pulsa Preparar cable ADB. Autoriza la PC en Android si se solicita. Después escribe el código aquí y pulsa Conectar.")
                    .setPositiveButton("Entendido", null).show();
        });
        add(card, adb, 7);
        connectionStatus = text("", 14, MUTED, false);
        connectionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(card, connectionStatus, 11);

        LinearLayout security = card(false);
        security.addView(title("Vinculación cifrada", 17));
        add(security, text("La primera vez, compara la identidad de Windows con la que aparece en Android. El teléfono recordará la identidad de esa PC.", 14, MUTED, false), 8);
        forget = button("Olvidar PC vinculada", false);
        forget.setOnClickListener(view -> forgetPc());
        add(security, forget, 11);
        add(content, columns(card, security), 13);
        return scroll(content);
    }

    private void selectMode(ReceiverLink.Mode next) {
        if (mode == next) return;
        rememberFields();
        mode = next;
        manual = false;
        savedIp = routeAddresses[mode.ordinal()];
        savedPort = routePorts[mode.ordinal()];
        address.setText(savedIp);
        port.setText(savedPort);
        refreshConnection();
    }

    private void connectOrDisconnect() {
        if (state != ReceiverLink.State.IDLE) { link.disconnect(); return; }
        rememberFields();
        if (!savedPin.matches("[0-9]{6}")) {
            showConnectionMessage("Escribe los seis dígitos que muestra Windows.", true);
            pin.requestFocus();
            return;
        }
        if (manual || mode == ReceiverLink.Mode.VPN) {
            InetAddress ip = validAddress(savedIp, mode);
            if (ip == null) {
                showConnectionMessage(mode == ReceiverLink.Mode.VPN ? "Escribe la IP de Tailscale que muestra tu PC."
                        : "Escribe la dirección IP que aparece en Windows.", true);
                return;
            }
            int number = portNumber(savedPort);
            if (number < 0) { showConnectionMessage("Usa un puerto de 1024 a 65535.", true); return; }
            closeKeyboard();
            link.connect(mode, new ReceiverLink.Endpoint(ip, number, null, "", mode, 0), false, savedPin, fingerprint);
            return;
        }
        List<ReceiverLink.Endpoint> candidates = discoveredDevices();
        if (candidates.isEmpty()) {
            if (fingerprint != null && validAddress(savedIp, mode) != null && portNumber(savedPort) >= 0) {
                ReceiverLink.Endpoint fallback = new ReceiverLink.Endpoint(validAddress(savedIp, mode), portNumber(savedPort),
                        fingerprint, computer, mode, 0);
                beginAutomatic(fallback);
            } else {
                showConnectionMessage(mode == ReceiverLink.Mode.USB
                        ? "Activa Compartir por USB. Si Windows muestra una IP para el cable, puedes introducirla manualmente."
                        : "Todavía no aparece tu PC. Inicia el emisor o introduce su IP.", true);
            }
        } else if (candidates.size() == 1 || fingerprint != null) {
            beginAutomatic(candidates.get(0));
        } else {
            String[] choices = new String[candidates.size()];
            for (int i = 0; i < choices.length; i++) choices[i] = candidates.get(i).computer + "  ·  " + candidates.get(i).address();
            dialog().setTitle("Elige tu computadora")
                    .setItems(choices, (dialog, which) -> beginAutomatic(candidates.get(which)))
                    .setNegativeButton("Cancelar", null).show();
        }
    }

    private void beginAutomatic(ReceiverLink.Endpoint endpoint) {
        closeKeyboard();
        link.connect(mode, endpoint, true, savedPin, fingerprint);
    }

    private List<ReceiverLink.Endpoint> discoveredDevices() {
        Map<String, ReceiverLink.Endpoint> devices = new LinkedHashMap<>();
        for (ReceiverLink.Endpoint candidate : offers) {
            if (candidate.mode != mode || (fingerprint != null && !fingerprint.equals(candidate.fingerprint))) continue;
            ReceiverLink.Endpoint previous = devices.get(candidate.fingerprint);
            if (previous == null || previous.observed < candidate.observed) devices.put(candidate.fingerprint, candidate);
        }
        return new ArrayList<>(devices.values());
    }

    private void forgetPc() {
        link.disconnect();
        fingerprint = null;
        computer = "";
        savedIp = "";
        savedPin = "";
        savedPort = Integer.toString(Wire.PORT);
        SharedPreferences.Editor editor = preferences.edit().remove("fingerprint").remove("computer");
        for (ReceiverLink.Mode route : ReceiverLink.Mode.values()) {
            routeAddresses[route.ordinal()] = "";
            routePorts[route.ordinal()] = savedPort;
            editor.remove("ip_" + route.name()).remove("port_" + route.name());
        }
        editor.apply();
        address.setText("");
        pin.setText("");
        port.setText(savedPort);
        showConnectionMessage("PC olvidada. Puedes vincular otro equipo.", false);
        refresh();
    }

    private void openTailscale() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
        try {
            startActivity(launch != null ? launch : new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://tailscale.com/download/android")));
        } catch (RuntimeException unavailable) {
            showConnectionMessage("Instala o abre Tailscale y conecta ambos equipos a la misma cuenta o red privada.", true);
        }
    }

    private void pasteRoute() {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData data = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence text = data == null || data.getItemCount() == 0 ? null : data.getItemAt(0).getText();
        String[] endpoint = text == null || text.length() > 64 ? new String[0] : text.toString().trim().split(":", -1);
        if (endpoint.length < 1 || endpoint.length > 2 || validAddress(endpoint[0], mode) == null
                || (endpoint.length == 2 && portNumber(endpoint[1]) < 0)) {
            showConnectionMessage("En Red del emisor, copia la dirección de esta conexión con su puerto.", true);
            return;
        }
        manual = true;
        address.setText(endpoint[0]);
        port.setText(endpoint.length == 2 ? endpoint[1] : Integer.toString(Wire.PORT));
        rememberFields();
        showConnectionMessage("Dirección preparada. Introduce el código de Windows.", false);
        pin.requestFocus();
    }

    private void openUsbSettings() {
        try { startActivity(new Intent("android.settings.TETHER_SETTINGS")); }
        catch (RuntimeException unsupported) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (RuntimeException ignored) { showConnectionMessage("Abre Ajustes y busca Compartir Internet por USB.", true); }
        }
    }

    private static InetAddress validAddress(String ip, ReceiverLink.Mode route) {
        InetAddress address = Wire.parseAddress(ip);
        if (address == null) return null;
        return (route == ReceiverLink.Mode.VPN ? Wire.isVpnAddress(address) : Wire.isLocalAddress(address)) ? address : null;
    }

    private static int portNumber(String value) {
        try { int number = Integer.parseInt(value); return number >= 1024 && number <= 65535 ? number : -1; }
        catch (NumberFormatException error) { return -1; }
    }

    private void rememberFields() {
        if (pin == null) return;
        savedPin = pin.getText().toString().trim();
        savedIp = address.getText().toString().trim();
        savedPort = port.getText().toString().trim();
        routeAddresses[mode.ordinal()] = savedIp;
        routePorts[mode.ordinal()] = savedPort;
    }

    private void refreshConnection() {
        if (connect == null) return;
        for (int i = 0; i < modeButtons.length; i++) {
            boolean selected = mode.ordinal() == i;
            if (Boolean.valueOf(selected).equals(modeButtons[i].getTag())) continue;
            modeButtons[i].setTag(selected);
            modeButtons[i].setTextColor(selected ? ACCENT : TEXT);
            modeButtons[i].setBackground(tappable(selected ? ACCENT_SURFACE : WELL, 10, selected ? ACCENT : BORDER));
            modeButtons[i].setSelected(selected);
        }
        routeHint.setText(mode == ReceiverLink.Mode.LAN ? "Conecta Android y Windows a la misma red."
                : mode == ReceiverLink.Mode.USB ? "Conecta un cable de datos, abre los ajustes de USB y activa Compartir Internet por USB. La PC aparecerá debajo."
                : "Abre Tailscale en los dos equipos con la misma cuenta. Copia la dirección Tailscale desde Red en el emisor e introdúcela aquí.");
        List<ReceiverLink.Endpoint> candidates = discoveredDevices();
        discovery.setVisibility(mode == ReceiverLink.Mode.VPN ? View.GONE : View.VISIBLE);
        discovery.setText(candidates.isEmpty() ? "Buscando la PC…" : candidates.size() == 1
                ? candidates.get(0).computer + "  ·  " + candidates.get(0).address()
                : candidates.size() + " PC disponibles");
        boolean busy = state != ReceiverLink.State.IDLE;
        boolean usb = mode == ReceiverLink.Mode.USB;
        tailscale.setVisibility(mode == ReceiverLink.Mode.VPN ? View.VISIBLE : View.GONE);
        boolean knownPrivateRoute = mode == ReceiverLink.Mode.VPN && validAddress(savedIp, mode) != null;
        savedRouteHint.setVisibility(knownPrivateRoute ? View.VISIBLE : View.GONE);
        if (knownPrivateRoute) savedRouteHint.setText("Dirección de tu PC guardada");
        pasteRoute.setVisibility(mode == ReceiverLink.Mode.VPN || manual ? View.VISIBLE : View.GONE);
        pasteRoute.setEnabled(!busy);
        for (Button route : modeButtons) route.setEnabled(!busy);
        adb.setEnabled(!busy);
        manualToggle.setEnabled(!busy);
        usbSettings.setVisibility(usb ? View.VISIBLE : View.GONE);
        adb.setVisibility(usb ? View.VISIBLE : View.GONE);
        boolean showIp = manual || mode == ReceiverLink.Mode.VPN;
        manualFields.setVisibility(showIp ? View.VISIBLE : View.GONE);
        manualToggle.setVisibility(mode == ReceiverLink.Mode.VPN ? View.GONE : View.VISIBLE);
        manualToggle.setText(showIp ? "Usar detección automática" : "Introducir IP manual");
        connect.setText(state == ReceiverLink.State.CONNECTED ? "Desconectar" : busy ? "Cancelar conexión" : "Conectar");
        forget.setEnabled(fingerprint != null);
        pin.setEnabled(!busy);
        address.setEnabled(!busy);
        port.setEnabled(!busy);
        connectionStatus.setText(status);
        connectionStatus.setTextColor(error ? WARNING : MUTED);
    }

    private void showConnectionMessage(String message, boolean failed) {
        status = message;
        error = failed;
        refreshConnection();
    }

    private void readPhoneBattery(Intent intent) {
        if (intent == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
        if (level >= 0 && scale > 0) phoneBattery = bounded(Math.round(level * 100f / scale), 100);
        phoneCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        paintPowerIcons();
        if (phoneLabel != null && phoneBattery >= 0) {
            phoneLabel.setText("Android " + phoneBattery + " %");
            phoneLabel.setContentDescription("Batería de Android " + phoneBattery + " por ciento");
        }
    }

    private void refresh() {
        if (homeStatus == null) return;
        boolean connected = state == ReceiverLink.State.CONNECTED;
        orbit.connectionChanged(connected);
        paintPowerIcons();
        homeOpen.setText(connected ? "Abrir escritorio  →" : "Vincular PC  →");
        String route = linkedMode == ReceiverLink.Mode.USB ? "USB" : linkedMode == ReceiverLink.Mode.VPN ? "Tailscale" : "red local";
        badge.setText(connected ? "Conectado" : state == ReceiverLink.State.IDLE ? "Sin conexión" : "Conectando");
        badge.setTextColor(connected ? ACCENT : MUTED);
        badge.setBackground(tappable(connected ? ACCENT_SURFACE : WELL, 16, BORDER));
        phoneLabel.setText(phoneBattery < 0 ? "" : "Android " + phoneBattery + " %");
        homeComputer.setText(computer.isEmpty() ? "Tu computadora" : computer);
        homeStatus.setText(connected ? "Cifrado activo · " + route + " · " + networkQuality() : status);
        homeBattery.setText(!connected ? "—" : battery < 0 ? "N/D" : battery + " %" + (charging ? "  ↗" : ""));
        homeBattery.setTextColor(battery >= 0 && battery <= 15 ? WARNING : TEXT);
        homeVolume.setText(volume < 0 || !connected ? "—" : muted ? "Silencio" : volume + " %");
        homeVolume.setTextColor(muted ? WARNING : TEXT);
        homeLatency.setText(!connected || latency < 0 ? "—" : latency + " ms");
        homeLatency.setTextColor(latency >= 0 && latency > 180 ? WARNING : TEXT);
        historyStatus.setText(latency < 0 ? "Las mediciones aparecerán cuando conectes tu PC."
                : latency + " ms ahora  ·  " + history.samples() + (history.samples() == 1 ? " muestra" : " muestras"));
        for (View control : powerButtons) control.setEnabled(connected);
        for (View control : audioButtons) control.setEnabled(connected && volume >= 0);
        for (View control : shortcutButtons) control.setEnabled(connected);
        slider.setEnabled(connected && volume >= 0);
        if (!volumeDragging) {
            slider.setProgress(Math.max(0, volume));
            volumeLabel.setText(volume < 0 || !connected ? "—" : volume + " %" + (muted ? "  ·  Silenciado" : ""));
        }
        mute.setText(muted ? "Reactivar sonido" : "Silenciar");
        result.setText(command.isEmpty() ? connected ? "" : "Conecta tu PC para usar los controles." : command);
        result.setTextColor(commandError ? WARNING : MUTED);
        result.setVisibility(result.length() == 0 ? View.GONE : View.VISIBLE);
        monitorHint.setText(!connected ? "Conecta tu PC para detectar sus pantallas."
                : secondary.isEmpty() ? monitorCount > 1 ? "En Pantallas de Orbivía para Windows, elige el monitor que quieres usar." : "Windows tiene una sola pantalla activa."
                : secondary + "  ·  " + monitorCount + " pantallas detectadas");
        if (waitingForMonitor) monitorHint.setText("Esperando la pantalla. Deja esta pestaña abierta mientras la preparas en Windows.");
        startSecond.setText(!connected ? "Conectar la PC" : !secondary.isEmpty() ? "Abrir segundo monitor"
                : waitingForMonitor ? "Cancelar espera" : "Abrir cuando esté lista");
        refreshQuality();
        refreshConnection();
        refreshViewerTools();
    }

    private void refreshQuality() {
        qualityButton.setText(Wire.RESOLUTIONS[quality]);
        qualityButton.setContentDescription("Resolución máxima " + Wire.RESOLUTIONS[quality]);
        rateButton.setText(Wire.FRAME_RATES[frameRate] + " FPS");
        rateButton.setContentDescription("Fluidez máxima " + Wire.FRAME_RATES[frameRate] + " fotogramas por segundo");
        resolutionHint.setText(adaptive ? "La calidad baja si la red o la PC se saturan." : "La resolución y los FPS elegidos son límites máximos.");
        measurement.setVisibility(lastVideo.isEmpty() ? View.GONE : View.VISIBLE);
        measurement.setText("Última medición  ·  " + lastVideo);
        String currentProfile = selectedProfile();
        if (profileHint != null) profileHint.setText("Perfil actual  ·  " + currentProfile);
        homeImageProfile.setText(currentProfile + "  ·  " + Wire.RESOLUTIONS[quality] + "  ·  "
                + Wire.FRAME_RATES[frameRate] + " FPS máx.");
        for (View tile : profileTiles) {
            boolean selected = currentProfile.equals(tile.getTag());
            if (tile.isSelected() != selected) {
                tile.setSelected(selected);
                tile.setBackground(tappable(selected ? ACCENT_SURFACE : PANEL, 17, selected ? ACCENT : BORDER));
            }
        }
    }

    private String selectedProfile() {
        for (Profile profile : Profile.values()) {
            if (quality == profile.quality && frameRate == profile.fps && adaptive == profile.adaptive) return profile.label;
        }
        return "Personalizado";
    }

    private void applyProfile(Profile profile) {
        if (quality == profile.quality && frameRate == profile.fps && adaptive == profile.adaptive) return;
        quality = profile.quality;
        frameRate = profile.fps;
        adaptive = profile.adaptive;
        adaptiveSwitch.setChecked(adaptive);
        saveVideoSettings();
    }

    private String networkQuality() {
        if (latency < 0) return "midiendo respuesta";
        if (latency <= 80) return "respuesta rápida";
        if (latency <= 180) return "enlace estable";
        return "respuesta lenta";
    }

    private void saveVideoSettings() {
        preferences.edit().putInt("quality", quality).putInt("fps", frameRate).putBoolean("adaptive", adaptive).apply();
        if (viewing != Wire.PAUSED) {
            desktop.endGesture();
            configureStream();
        }
        refreshQuality();
        refreshViewerTools();
    }

    private void showPage(int index, boolean animate) {
        boolean entering = animate && index != page && animationsEnabled();
        int direction = index >= page ? 1 : -1;
        if (page == CONNECTION && index != CONNECTION) { rememberFields(); closeKeyboard(); }
        for (int i = 0; i < pages.length; i++) {
            pages[i].animate().cancel();
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            navigation[i].active(i == index);
        }
        if (index != MONITOR && waitingForMonitor) {
            waitingForMonitor = false;
            refresh();
        }
        page = index;
        View current = pages[page];
        current.setAlpha(entering ? .15f : 1f);
        current.setTranslationX(entering ? direction * dp(18) : 0f);
        if (entering) {
            if (current instanceof ScrollView) {
                LinearLayout content = (LinearLayout) ((ScrollView) current).getChildAt(0);
                for (int i = 0; i < Math.min(4, content.getChildCount()); i++) {
                    View item = content.getChildAt(i);
                    item.animate().cancel();
                    item.setTranslationY(dp(8));
                    item.animate().translationY(0).setStartDelay(i * 22L).setDuration(190)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            }
            current.animate().alpha(1f).translationX(0f).setDuration(255)
                    .setInterpolator(new DecelerateInterpolator()).start();
            if (index == HOME) orbit.enter();
        }
    }

    private void openViewer(int desired, DesktopView.Mode mode) {
        if (state != ReceiverLink.State.CONNECTED) {
            showPage(CONNECTION, true);
            showConnectionMessage("Conecta tu PC para abrir el escritorio.", false);
            return;
        }
        viewerExpanded = false;
        viewing = desired;
        setViewerMode(mode);
        closeKeyboard();
        configureStream();
        setViewerVisibility();
        if (animationsEnabled()) {
            viewerHost.setAlpha(.65f);
            viewerHost.animate().alpha(1f).setDuration(140).start();
        }
    }

    private void closeViewer() {
        dismissViewerDialogs();
        desktop.endGesture();
        viewing = Wire.PAUSED;
        viewerExpanded = false;
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        configureStream();
        setViewerVisibility();
        refreshQuality();
    }

    private void configureStream() {
        if (viewing != Wire.PAUSED || gotFrame) clearFrame();
        revision = link.show(foreground ? viewing : Wire.PAUSED, quality, Wire.FRAME_RATES[frameRate], adaptive);
    }

    private void setViewerVisibility() {
        boolean active = viewing != Wire.PAUSED;
        viewerHost.setVisibility(active ? View.VISIBLE : View.GONE);
        videoHint.setVisibility(gotFrame ? View.GONE : View.VISIBLE);
        updateViewerHandle();
        shell.setVisibility(active ? View.GONE : View.VISIBLE);
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(active || dark ? 0 : lightBars, lightBars);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (active) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (active) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            else if (!dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else getWindow().setNavigationBarColor(INK);
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            if (active || Build.VERSION.SDK_INT >= 26) getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        root.requestApplyInsets();
    }

    private void clearFrame() {
        gotFrame = false;
        liveVideo = "";
        frameCount = 0;
        meterStart = 0;
        desktop.image(null);
        VideoFrame stale = latestFrame.getAndSet(null);
        if (stale != null) stale.image.recycle();
        if (videoHint != null) {
            videoHint.setText(state == ReceiverLink.State.RETRYING ? "Reconectando con tu PC…" : "Preparando imagen…");
            videoHint.setVisibility(View.VISIBLE);
        }
    }

    private void queueFrame(Bitmap image, int revision, int sequence) {
        VideoFrame overwritten = latestFrame.getAndSet(new VideoFrame(image, revision, sequence));
        if (overwritten != null) overwritten.image.recycle();
        if (drawScheduled.compareAndSet(false, true)) main.post(this::drawLatest);
    }

    private void drawLatest() {
        VideoFrame frame = latestFrame.getAndSet(null);
        if (frame != null) {
            if (frame.revision == revision && viewing != Wire.PAUSED && foreground && state == ReceiverLink.State.CONNECTED) {
                boolean first = !gotFrame;
                gotFrame = true;
                visibleSequence = frame.sequence;
                desktop.image(frame.image);
                if (first) videoHint.setVisibility(View.GONE);
                long now = SystemClock.elapsedRealtime();
                if (meterStart == 0) meterStart = now;
                frameCount++;
                if (now - meterStart >= 1200) {
                    int fps = (int) Math.round(1000.0 * frameCount / (now - meterStart));
                    lastVideo = frame.image.getWidth() + " × " + frame.image.getHeight() + " · " + fps + " FPS";
                    liveVideo = lastVideo;
                    refreshViewerTools();
                    frameCount = 0;
                    meterStart = now;
                } else if (first) refreshViewerTools();
            } else frame.image.recycle();
        }
        drawScheduled.set(false);
        if (latestFrame.get() != null && drawScheduled.compareAndSet(false, true)) main.post(this::drawLatest);
    }

    private boolean padReady() {
        return foreground && viewing != Wire.PAUSED && viewerMode == DesktopView.Mode.TOUCHPAD
                && state == ReceiverLink.State.CONNECTED && gotFrame;
    }

    private void sendPadClick(boolean twice) {
        if (padReady()) link.leftClick(visibleSequence, twice);
    }

    private void setViewerMode(DesktopView.Mode mode) {
        viewerMode = mode;
        desktop.mode(mode);
        updateViewerHandle();
    }

    private LinearLayout buildViewerDock() {
        LinearLayout dock = vertical();
        viewerHandle = button("Controles del visor  ⋯", false);
        viewerHandle.setTextSize(13);
        viewerHandle.setPadding(dp(12), 0, dp(12), 0);
        viewerHandle.setTextColor(ON_INK);
        viewerHandle.setBackground(tappable(0xE5291D3C, 16, 0x8AFFB295));
        viewerHandle.setOnClickListener(view -> toggleViewerDock(!viewerExpanded));
        dock.addView(viewerHandle, new LinearLayout.LayoutParams(-2, -2, 0f));
        ((LinearLayout.LayoutParams) viewerHandle.getLayoutParams()).gravity = Gravity.END;

        viewerPanel = vertical();
        viewerPanel.setPadding(dp(12), dp(10), dp(12), dp(11));
        viewerPanel.setBackground(gradientSurface(new int[]{0xFF30243A, 0xFF241C30}, 18, 0xFF72536F));
        viewerPanel.setElevation(dp(8));
        viewerPanel.setClickable(true);
        viewerSummary = text("", 12, 0xFFF4D5E0, true);
        viewerPanel.addView(viewerSummary);
        LinearLayout modes = horizontal();
        addQuickMode(modes, "Directo", "touch", DesktopView.Mode.DIRECT);
        addQuickMode(modes, "Panel táctil", "screen", DesktopView.Mode.TOUCHPAD);
        addQuickMode(modes, "Solo ver", "eye", DesktopView.Mode.VIEW_ONLY);
        add(viewerPanel, modes, 8);

        quickKeyboard = dockAction("Teclado", "keyboard", () -> { toggleViewerDock(false); showKeyboard(); });
        quickCapture = dockAction("Captura", "camera", () -> {
            toggleViewerDock(false);
            capture.chooseLocation(this, desktop.currentFrame());
        });
        quickDisplay = dockAction("Monitor", "dual", this::switchViewerDisplay);
        LinearLayout actions = horizontal();
        addSegment(actions, quickKeyboard);
        addSegment(actions, quickCapture);
        addSegment(actions, quickDisplay);
        add(viewerPanel, actions, 6);
        LinearLayout tools = horizontal();
        addSegment(tools, dockAction("Girar", "rotate", this::rotateViewer));
        addSegment(tools, dockAction("Ajustes", "sliders", () -> {
            toggleViewerDock(false);
            openViewerTools();
        }));
        addSegment(tools, dockAction("Salir", "exit", this::closeViewer));
        add(viewerPanel, tools, 6);
        add(dock, viewerPanel, 6);
        return dock;
    }

    private void addQuickMode(LinearLayout row, String label, String icon, DesktopView.Mode mode) {
        Button item = dockAction(label, icon, () -> {
            setViewerMode(mode);
            toggleViewerDock(false);
        });
        quickModes[mode.ordinal()] = item;
        addSegment(row, item);
    }

    private Button dockAction(String label, String icon, Runnable action) {
        Button item = viewerAction(label, action);
        item.setTextSize(12);
        item.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{0xFFC8B5C8, ON_INK}));
        item.setBackground(tappable(0xFF43354E, 11, 0));
        item.setCompoundDrawables(null, Icon.drawable(icon, PEACH, dp(19)), null, null);
        item.setCompoundDrawablePadding(dp(3));
        return item;
    }

    private void toggleViewerDock(boolean expanded) {
        if (viewerExpanded == expanded) return;
        desktop.endGesture();
        viewerExpanded = expanded;
        updateViewerHandle();
        viewerPanel.animate().cancel();
        viewerPanel.setAlpha(1f);
        viewerPanel.setTranslationY(0f);
        if (expanded && animationsEnabled()) {
            viewerPanel.setAlpha(.35f);
            viewerPanel.setTranslationY(-dp(8));
            viewerPanel.animate().alpha(1f).translationY(0f).setDuration(185)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void updateViewerHandle() {
        if (viewerHandle == null) return;
        viewerHandle.setText(viewerExpanded ? "Ocultar controles  ▴" : "Controles del visor  ⋯");
        viewerPanel.setVisibility(viewerExpanded ? View.VISIBLE : View.GONE);
        for (Button item : quickModes) {
            boolean selected = item == quickModes[viewerMode.ordinal()];
            item.setSelected(selected);
            item.setBackground(tappable(selected ? 0xFF644154 : 0xFF43354E, 11, selected ? PEACH : 0));
        }
        refreshViewerTools();
    }

    private void rotateViewer() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void switchViewerDisplay() {
        if (state != ReceiverLink.State.CONNECTED) return;
        if (viewing != Wire.SECOND_DISPLAY && secondary.isEmpty()) {
            closeViewer();
            showPage(MONITOR, true);
            return;
        }
        toggleViewerDock(false);
        viewing = viewing == Wire.SECOND_DISPLAY ? Wire.DESKTOP : Wire.SECOND_DISPLAY;
        configureStream();
        setViewerVisibility();
    }

    private void openViewerTools() {
        if (viewing == Wire.PAUSED || viewerDialog != null || textDialog != null) return;
        desktop.endGesture();
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(8), dp(20), dp(18));
        viewerStats = text("", 14, ACCENT, true);
        body.addView(viewerStats);
        if (viewerMode == DesktopView.Mode.VIEW_ONLY) {
            add(body, text("Pellizca para ampliar y arrastra para recorrer la imagen.", 12, MUTED, false), 6);
            Button zoom = viewerAction("Ampliar", () -> { viewerDialog.dismiss(); desktop.zoomIn(); });
            Button fit = viewerAction("Ajustar a pantalla", () -> { viewerDialog.dismiss(); desktop.resetZoom(); });
            add(body, tilePair(zoom, fit), 7);
        }
        add(body, title("Perfil de imagen", 15), 15);
        add(body, tilePair(viewerProfile(Profile.SAVER), viewerProfile(Profile.BALANCED)), 5);
        add(body, tilePair(viewerProfile(Profile.SMOOTH), viewerProfile(Profile.FULL_HD)), 5);

        viewerAudio = title("", 15);
        add(body, viewerAudio, 15);
        viewerLower = viewerAction("−5 %", () -> execute(Wire.VOLUME_DOWN));
        viewerRaise = viewerAction("+5 %", () -> execute(Wire.VOLUME_UP));
        viewerMute = viewerAction("Silenciar", () -> execute(Wire.MUTE));
        LinearLayout volumeActions = horizontal();
        addSegment(volumeActions, viewerLower);
        addSegment(volumeActions, viewerMute);
        addSegment(volumeActions, viewerRaise);
        add(body, volumeActions, 5);
        viewerNotice = text("", 12, MUTED, false);
        viewerNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(body, viewerNotice, 3);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        viewerDialog = dialog()
                .setTitle(viewing == Wire.SECOND_DISPLAY ? "Segundo monitor" : "Escritorio remoto")
                .setView(scroll)
                .setNeutralButton("Salir del visor", (dialog, which) -> closeViewer())
                .setPositiveButton("Volver", null)
                .create();
        viewerDialog.setOnDismissListener(dialog -> {
            viewerDialog = null;
            viewerStats = viewerAudio = viewerNotice = null;
            viewerLower = viewerRaise = viewerMute = null;
        });
        refreshViewerTools();
        viewerDialog.show();
    }

    private void addSegment(LinearLayout row, Button item) {
        LinearLayout.LayoutParams space = weighted(1);
        if (row.getChildCount() > 0) space.setMarginStart(dp(6));
        row.addView(item, space);
    }

    private Button viewerAction(String label, Runnable action) {
        Button item = button(label, false);
        item.setTextSize(13);
        item.setOnClickListener(view -> action.run());
        return item;
    }

    private Button viewerProfile(Profile profile) {
        boolean current = profile.label.equals(selectedProfile());
        Button item = button(profile.label + "\n" + profile.limits(), current);
        item.setTextSize(12);
        item.setSelected(current);
        item.setOnClickListener(view -> { viewerDialog.dismiss(); applyProfile(profile); });
        return item;
    }

    private void refreshViewerTools() {
        boolean connected = state == ReceiverLink.State.CONNECTED;
        if (padButtons != null) {
            padButtons.setVisibility(viewing != Wire.PAUSED && viewerMode == DesktopView.Mode.TOUCHPAD && !viewerExpanded
                    ? View.VISIBLE : View.GONE);
            boolean enabled = padReady();
            for (int i = 0; i < padButtons.getChildCount(); i++) padButtons.getChildAt(i).setEnabled(enabled);
        }
        String image = liveVideo.isEmpty() ? (gotFrame ? "Midiendo imagen" : "Sin imagen") : liveVideo;
        String measurements = image + (connected && latency >= 0 ? "  ·  " + latency + " ms" : "");
        if (viewerSummary != null && viewerExpanded) {
            viewerSummary.setText((viewing == Wire.SECOND_DISPLAY ? "Monitor extendido" : "Escritorio remoto") + "  ·  " + measurements);
            quickCapture.setEnabled(gotFrame && connected && capture.available());
            quickKeyboard.setEnabled(gotFrame && connected && viewerMode != DesktopView.Mode.VIEW_ONLY);
            quickDisplay.setText(viewing == Wire.SECOND_DISPLAY ? "Escritorio" : secondary.isEmpty() ? "Preparar" : "Monitor");
            quickDisplay.setEnabled(connected);
        }
        if (viewerStats == null) return;
        viewerStats.setText(measurements);
        boolean audio = connected && volume >= 0;
        viewerAudio.setText(audio ? "Volumen de Windows  ·  " + volume + " %" : "Volumen de Windows");
        viewerLower.setEnabled(audio);
        viewerRaise.setEnabled(audio);
        viewerMute.setEnabled(audio);
        viewerMute.setText(muted ? "Reactivar" : "Silenciar");
        viewerNotice.setText(command);
        viewerNotice.setTextColor(commandError ? WARNING : MUTED);
        viewerNotice.setVisibility(command.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void dismissViewerDialogs() {
        if (viewerDialog != null) viewerDialog.dismiss();
        if (textDialog != null) textDialog.dismiss();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        capture.result(this, request, result, data);
    }

    private void showKeyboard() {
        if (!gotFrame || textDialog != null || viewerMode == DesktopView.Mode.VIEW_ONLY) return;
        EditText input = field("Texto para Windows", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
        textDialog = dialog().setTitle("Escribir en la PC").setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Enviar", (window, which) -> {
                    String value = input.getText().toString();
                    if (!value.isEmpty() && (!gotFrame || !link.text(value, visibleSequence)))
                        Toast.makeText(this, "No se pudo enviar el texto.", Toast.LENGTH_SHORT).show();
                }).create();
        input.requestFocus();
        textDialog.setOnDismissListener(window -> { textDialog = null; setViewerVisibility(); });
        textDialog.show();
        if (textDialog.getWindow() != null)
            textDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void execute(int order, int... values) {
        boolean sent = link.send(order, values);
        command = sent ? "Esperando respuesta de Windows…" : "La conexión se interrumpió. Vuelve a intentarlo.";
        commandError = !sent;
        refresh();
    }

    private void applyVolumeFromAccessibility() {
        if (slider != null && slider.isEnabled()) execute(Wire.VOLUME_SET, slider.getProgress());
    }

    private void closeKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        focus.clearFocus();
    }

    @Override public void onBackPressed() {
        if (viewing != Wire.PAUSED) {
            if (viewerExpanded) toggleViewerDock(false);
            else closeViewer();
            return;
        }
        if (page != HOME) { showPage(HOME, true); return; }
        super.onBackPressed();
    }

    private final class LinkEvents implements ReceiverLink.Listener {
        @Override public void state(ReceiverLink.State next, String message, boolean failed) {
            state = next;
            status = message;
            error = failed;
            if (next != ReceiverLink.State.CONNECTED) {
                latency = -1;
                history.clear();
                volume = battery = -1;
                muted = false;
                monitorCount = 0;
                secondary = "";
                waitingForMonitor = false;
                if ("Esperando respuesta de Windows…".equals(command)) {
                    command = "La conexión se interrumpió antes de que Windows respondiera.";
                    commandError = true;
                }
                desktop.endGesture();
                clearFrame();
                if (next == ReceiverLink.State.IDLE && viewing != Wire.PAUSED) { closeViewer(); showPage(CONNECTION, true); }
                if (verifyDialog != null && next != ReceiverLink.State.VERIFYING) verifyDialog.dismiss();
            }
            refresh();
        }

        @Override public void nearby(List<ReceiverLink.Endpoint> devices) { offers = devices; refreshConnection(); }

        @Override public void verify(String code, ReceiverLink.Approval decision) {
            if (!decision.pending() || isFinishing()) { decision.answer(false); return; }
            LinearLayout display = vertical();
            display.setPadding(dp(22), dp(4), dp(22), dp(5));
            TextView identity = text(code, 19, ACCENT, true);
            identity.setTypeface(Typeface.MONOSPACE);
            add(display, text("Busca «Identidad de este equipo» en el emisor de Windows. Acepta si los cuatro grupos coinciden exactamente.", 15, TEXT, false), 0);
            add(display, identity, 11);
            verifyDialog = dialog().setTitle("Verificar PC").setView(display)
                    .setPositiveButton("Coincide", (dialog, which) -> decision.answer(true))
                    .setNegativeButton("Cancelar", (dialog, which) -> decision.answer(false)).create();
            verifyDialog.setOnDismissListener(dialog -> { decision.answer(false); verifyDialog = null; });
            verifyDialog.show();
        }

        @Override public void linked(String name, String verified, ReceiverLink.Endpoint endpoint, ReceiverLink.Mode route) {
            computer = name;
            fingerprint = verified;
            linkedMode = route;
            routeAddresses[route.ordinal()] = endpoint.ip.getHostAddress();
            routePorts[route.ordinal()] = Integer.toString(endpoint.port);
            if (mode == route) {
                address.setText(routeAddresses[route.ordinal()]);
                port.setText(routePorts[route.ordinal()]);
                rememberFields();
            }
            preferences.edit().putString("computer", computer).putString("fingerprint", fingerprint)
                    .putString("ip_" + route.name(), routeAddresses[route.ordinal()])
                    .putString("port_" + route.name(), routePorts[route.ordinal()])
                    .putInt("mode", route.ordinal()).apply();
            command = "";
        }

        @Override public void vpnRoute(String ip, int privatePort) {
            if (ip.isEmpty() || fingerprint == null) return;
            int index = ReceiverLink.Mode.VPN.ordinal();
            routeAddresses[index] = ip;
            routePorts[index] = Integer.toString(privatePort);
            if (mode == ReceiverLink.Mode.VPN && state == ReceiverLink.State.CONNECTED) {
                address.setText(ip);
                port.setText(routePorts[index]);
                rememberFields();
            }
            preferences.edit().putString("ip_VPN", ip).putString("port_VPN", routePorts[index]).apply();
            refreshConnection();
        }

        @Override public void screens(int count, String target) {
            monitorCount = count;
            secondary = target;
            if (waitingForMonitor && !target.isEmpty() && foreground && page == MONITOR && state == ReceiverLink.State.CONNECTED) {
                waitingForMonitor = false;
                openViewer(Wire.SECOND_DISPLAY, DesktopView.Mode.DIRECT);
            }
            if (viewing == Wire.SECOND_DISPLAY && target.isEmpty()) {
                closeViewer();
                showPage(MONITOR, true);
            }
            refresh();
        }

        @Override public void result(boolean ok, String message) {
            if (message.startsWith("STAT:") || message.startsWith("VOL:")) {
                String[] values = message.split(":", -1);
                if (values.length >= 3) {
                    volume = percent(values[1]);
                    muted = "1".equals(values[2]);
                }
                if (values.length == 5) { battery = percent(values[3]); charging = "1".equals(values[4]); }
                if (message.startsWith("VOL:")) { command = "Volumen actualizado"; commandError = false; }
            } else {
                command = message;
                commandError = !ok;
                if (!ok && viewing != Wire.PAUSED && !gotFrame) videoHint.setText(message);
            }
            refresh();
        }

        @Override public void latency(long delay) {
            latency = delay;
            history.record(delay);
            refresh();
        }
        @Override public void frame(Bitmap image, int sourceRevision, int sequence) { queueFrame(image, sourceRevision, sequence); }
    }

    private Button control(String label, int command, String confirmation, List<View> group) {
        Button button = button(label, false);
        String symbol;
        switch (command) {
            case Wire.MUTE: symbol = "mute"; break;
            case Wire.VOLUME_UP: symbol = "volume"; break;
            case Wire.VOLUME_DOWN: symbol = "volume-low"; break;
            case Wire.LOCK: symbol = "lock"; break;
            case Wire.SLEEP: symbol = "sleep"; break;
            case Wire.RESTART: symbol = "restart"; break;
            default: symbol = "power";
        }
        decorate(button, symbol, false);
        if (command == Wire.SHUTDOWN) button.setBackground(tappable(DANGER, 11, BORDER));
        button.setOnClickListener(view -> {
            if (confirmation == null) execute(command);
            else dialog().setTitle(button.getText()).setMessage(confirmation)
                    .setNegativeButton("Cancelar", null).setPositiveButton("Confirmar", (dialog, which) -> execute(command)).show();
        });
        group.add(button);
        return button;
    }

    private Button shortcut(String label, int shortcut) {
        Button action = button(label, false);
        String[] icons = {"", "screen", "dual", "sliders", "play", "prev", "next"};
        decorate(action, icons[shortcut], shortcut >= 4);
        action.setContentDescription("Enviar atajo de Windows: " + label);
        action.setOnClickListener(view -> execute(Wire.SHORTCUT, shortcut));
        shortcutButtons.add(action);
        return action;
    }

    private View profileButton(Profile profile) {
        LinearLayout option = card(true);
        option.setMinimumHeight(dp(76));
        option.addView(title(profile.label, 15));
        add(option, text(profile.limits(), 12, MUTED, false), 3);
        option.setContentDescription(profile.label + ", " + profile.limits());
        option.setTag(profile.label);
        pressMotion(option);
        profileTiles.add(option);
        option.setOnClickListener(view -> {
            applyProfile(profile);
            option.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        });
        return option;
    }

    private View devicePower() {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        deviceBatteryIcon = new Icon(this, "battery", ACCENT);
        row.addView(deviceBatteryIcon, lp(dp(24), dp(24)));
        row.addView(phoneLabel);
        return row;
    }

    private void paintPowerIcons() {
        if (deviceBatteryIcon != null) deviceBatteryIcon.battery(phoneBattery, phoneCharging, animationsEnabled());
        if (pcBatteryIcon != null) pcBatteryIcon.battery(battery, charging && battery >= 0, animationsEnabled());
        if (speakerIcon != null) speakerIcon.symbol(muted ? "mute" : volume < 45 ? "volume-low" : "volume");
    }

    private View featureTile(String symbol, String heading, String detail, boolean warm, Runnable action) {
        LinearLayout tile = vertical();
        int fill = warm ? (dark ? 0xFF422F32 : 0xFFFFE8D9) : ACCENT_SURFACE;
        int ink = warm ? (dark ? 0xFFFFC5AA : 0xFF75391F) : ACCENT;
        tile.setBackground(tappable(fill, 20, 0));
        tile.setPadding(dp(13), dp(12), dp(13), dp(13));
        LinearLayout top = horizontal();
        top.addView(new Icon(this, symbol, ink), lp(dp(29), dp(29)));
        TextView arrow = text("↗", 17, ink, true);
        arrow.setGravity(Gravity.END);
        top.addView(arrow, weighted(1));
        tile.addView(top);
        TextView name = text(heading, 15, TEXT, true);
        add(tile, name, 8);
        add(tile, text(detail, 12, ink, false), 1);
        tile.setContentDescription(heading + ". " + detail);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> action.run());
        pressMotion(tile);
        return tile;
    }

    private View metricCard(String icon, String label, TextView value) {
        LinearLayout metric = vertical();
        metric.setPadding(dp(8), dp(10), dp(8), dp(10));
        metric.setGravity(Gravity.CENTER_HORIZONTAL);
        Icon glyph = new Icon(this, icon, ACCENT);
        if (icon.equals("battery")) pcBatteryIcon = glyph;
        if (icon.equals("volume")) speakerIcon = glyph;
        glyph.setBackground(surface(ACCENT_SURFACE, 14, 0));
        glyph.setPadding(dp(6), dp(6), dp(6), dp(6));
        metric.addView(glyph, lp(dp(36), dp(36)));
        TextView caption = text(label, 11, MUTED, true);
        caption.setGravity(Gravity.CENTER);
        value.setGravity(Gravity.CENTER);
        add(metric, value, 4);
        add(metric, caption, 1);
        metric.setFocusable(true);
        metric.setBackground(tappable(PANEL, 16, 0));
        metric.setOnClickListener(view -> {
            if (icon.equals("volume")) showPage(CONTROLS, true);
            else if (icon.equals("latency")) {
                ScrollView scroll = (ScrollView) pages[HOME];
                android.graphics.Rect area = new android.graphics.Rect();
                history.getDrawingRect(area);
                scroll.offsetDescendantRectToMyCoords(history, area);
                scroll.smoothScrollTo(0, Math.max(0, area.top - dp(64)));
                history.announceForAccessibility(historyStatus.getText());
            } else {
                String pc = state != ReceiverLink.State.CONNECTED ? "Sin conexión" : battery < 0 ? "Sin lectura" : battery + " %" + (charging ? " · Con alimentación" : " · En batería");
                String android = phoneBattery < 0 ? "Sin lectura" : phoneBattery + " %" + (phoneCharging ? " · Con alimentación" : " · En batería");
                dialog().setTitle("Baterías").setMessage("Windows   " + pc + "\n\nAndroid   " + android)
                        .setPositiveButton("Cerrar", null).show();
            }
        });
        pressMotion(metric);
        return metric;
    }

    private View actionRow(String symbol, String heading, String detail, Runnable action) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(67));
        row.setPadding(dp(11), dp(8), dp(12), dp(8));
        row.setBackground(tappable(PANEL, 12, BORDER));
        row.addView(iconBadge(symbol), lp(dp(38), dp(38)));
        LinearLayout titles = vertical();
        titles.addView(title(heading, 15));
        add(titles, text(detail, 12, MUTED, false), 2);
        LinearLayout.LayoutParams labels = weighted(1f);
        labels.setMargins(dp(10), 0, dp(5), 0);
        row.addView(titles, labels);
        row.addView(text("›", 23, ACCENT, false));
        row.setFocusable(true);
        row.setContentDescription(heading + ". " + detail);
        row.setOnClickListener(view -> action.run());
        pressMotion(row);
        return row;
    }

    private static int percent(String value) {
        try { return bounded(Integer.parseInt(value), 100); }
        catch (NumberFormatException absent) { return -1; }
    }

    private static int bounded(int value, int max) { return Math.max(0, Math.min(max, value)); }

    private LinearLayout page(String heading) {
        LinearLayout column = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(dp(1060), MeasureSpec.getSize(widthSpec));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(wide ? 25 : 15), dp(wide ? 24 : 13), dp(wide ? 25 : 15), dp(18));
        if (heading != null) column.addView(editorial(heading, 32, TEXT));
        return column;
    }

    private View scroll(LinearLayout contents) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(contents, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        return scroll;
    }

    private LinearLayout columns(View left, View right) {
        if (wide) return tilePair(left, right);
        LinearLayout stacked = vertical();
        stacked.addView(left, lp(-1, -2));
        add(stacked, right, 9);
        return stacked;
    }

    private LinearLayout tilePair(View left, View right) {
        LinearLayout row = horizontal();
        row.setBaselineAligned(false);
        row.addView(left, weighted(1f));
        LinearLayout.LayoutParams gap = weighted(1f);
        gap.setMarginStart(dp(9));
        row.addView(right, gap);
        return row;
    }

    private LinearLayout card(boolean action) {
        LinearLayout view = vertical();
        view.setPadding(dp(14), dp(13), dp(14), dp(13));
        view.setBackground(action ? tappable(PANEL, 13, BORDER) : surface(PANEL, 13, BORDER));
        return view;
    }

    private GradientDrawable gradientSurface(int[] colors, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private View iconBadge(String name) {
        Icon icon = new Icon(this, name, ACCENT);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setBackground(surface(ACCENT_SURFACE, 10, 0));
        return icon;
    }

    private Button button(String label, boolean primary) {
        Button result = new Button(this);
        result.setText(label);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setPadding(dp(6), dp(7), dp(6), dp(7));
        result.setMinHeight(dp(48));
        result.setMinimumHeight(dp(48));
        result.setMinimumWidth(0);
        result.setMinWidth(0);
        result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        result.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{MUTED, primary ? ON_ACCENT : TEXT}));
        pressMotion(result);
        StateListDrawable fill = new StateListDrawable();
        fill.addState(new int[]{-android.R.attr.state_enabled}, surface(WELL, 12, BORDER));
        fill.addState(new int[0], tappable(primary ? ACCENT : WELL, 12, primary ? ACCENT : BORDER));
        result.setBackground(fill);
        return result;
    }

    private void decorate(Button button, String symbol, boolean stacked) {
        android.graphics.drawable.Drawable glyph = Icon.drawable(symbol, ACCENT, dp(stacked ? 23 : 21));
        button.setCompoundDrawablePadding(dp(stacked ? 3 : 7));
        if (stacked) button.setCompoundDrawables(null, glyph, null, null);
        else {
            button.setPadding(dp(12), dp(8), dp(12), dp(8));
            button.setCompoundDrawablesRelative(glyph, null, null, null);
        }
        button.setCompoundDrawableTintList(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{MUTED, ACCENT}));
    }

    private EditText field(String placeholder, int inputType) {
        EditText input = new EditText(this);
        input.setHint(placeholder);
        input.setInputType(inputType);
        input.setTextSize(15);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setMinHeight(dp(48));
        input.setBackground(surface(BG, 11, BORDER));
        return input;
    }

    private TextView pill(String label, int tint, int fill) {
        TextView view = text(label, 11, tint, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(40));
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setBackground(tappable(fill, 16, BORDER));
        return view;
    }

    private TextView title(String label, int size) { return text(label, size, TEXT, true); }

    private TextView editorial(String label, int size, int tint) {
        TextView view = text(label, size, tint, false);
        view.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        return view;
    }

    private static boolean animationsEnabled() {
        return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
    }

    private static void pressMotion(View view) {
        StateListAnimator animator = new StateListAnimator();
        animator.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled}, scale(view, .97f, 85));
        animator.addState(new int[0], scale(view, 1f, 155));
        view.setStateListAnimator(animator);
    }

    private static AnimatorSet scale(View target, float value, int duration) {
        AnimatorSet scale = new AnimatorSet();
        scale.playTogether(ObjectAnimator.ofFloat(target, View.SCALE_X, value),
                ObjectAnimator.ofFloat(target, View.SCALE_Y, value));
        scale.setDuration(duration);
        return scale;
    }

    private TextView text(String label, int size, int tint, boolean strong) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(tint);
        view.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private GradientDrawable surface(int color, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private RippleDrawable tappable(int color, int radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x40FFC9DE : 0x30823D5F), surface(color, radius, stroke), surface(Color.WHITE, radius, 0));
    }

    private LinearLayout vertical() { LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); return column; }
    private LinearLayout horizontal() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); return row; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams weighted(float weight) { return new LinearLayout.LayoutParams(0, -2, weight); }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams layout = lp(-1, -2);
        layout.topMargin = dp(top);
        parent.addView(child, layout);
    }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private final class NavItem extends LinearLayout {
        private final Icon icon;
        private final TextView caption;
        NavItem(String label, String symbol) {
            super(MainActivity.this);
            setOrientation(wide ? HORIZONTAL : VERTICAL);
            setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
            setMinimumHeight(dp(54));
            setPadding(dp(wide ? 9 : 1), dp(5), dp(wide ? 7 : 1), dp(5));
            setFocusable(true);
            pressMotion(this);
            icon = new Icon(MainActivity.this, symbol, MUTED);
            addView(icon, lp(dp(21), dp(21)));
            caption = text(label, wide ? 12 : 10, MUTED, true);
            caption.setGravity(wide ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
            LinearLayout.LayoutParams labelSpace = wide ? weighted(1f) : lp(-1, -2);
            labelSpace.setMarginStart(dp(wide ? 7 : 0));
            labelSpace.topMargin = dp(wide ? 0 : 3);
            addView(caption, labelSpace);
            setContentDescription(label);
        }

        void active(boolean selected) {
            boolean changed = isSelected() != selected;
            setSelected(selected);
            icon.tint(selected ? ACCENT : MUTED);
            caption.setTextColor(selected ? ACCENT : MUTED);
            setBackground(tappable(selected ? ACCENT_SURFACE : HEADER, 12, 0));
            if (changed && selected && isShown() && animationsEnabled()) {
                icon.animate().cancel();
                icon.setTranslationY(dp(3));
                icon.animate().translationY(0f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
            }
        }
    }

    private static final class VideoFrame {
        final Bitmap image;
        final int revision;
        final int sequence;
        VideoFrame(Bitmap image, int revision, int sequence) { this.image = image; this.revision = revision; this.sequence = sequence; }
    }
}
