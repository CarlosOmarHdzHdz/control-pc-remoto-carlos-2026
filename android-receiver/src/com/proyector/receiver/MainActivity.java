package com.proyector.receiver;

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
    private static final int BG = Color.rgb(14, 22, 33);
    private static final int HEADER = Color.rgb(18, 29, 42);
    private static final int PANEL = Color.rgb(24, 37, 52);
    private static final int WELL = Color.rgb(33, 51, 68);
    private static final int BORDER = Color.rgb(48, 69, 87);
    private static final int TEXT = Color.rgb(240, 247, 251);
    private static final int MUTED = Color.rgb(171, 192, 208);
    private static final int ACCENT = Color.rgb(108, 230, 211);
    private static final int ACCENT_SURFACE = Color.rgb(32, 68, 70);
    private static final int WARNING = Color.rgb(255, 195, 139);
    private static final int DANGER = Color.rgb(83, 44, 55);
    private static final int HOME = 0, REMOTE = 1, MONITOR = 2, CONTROLS = 3, CONNECTION = 4;
    private static final String[] TAB_NAMES = {"Inicio", "Remoto", "Monitor", "Controles", "Conexión"};
    private static final String[] TAB_ICONS = {"home", "screen", "dual", "sliders", "link"};

    private final Handler main = new Handler(Looper.getMainLooper());
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
    private TextView badge, phoneLabel, homeComputer, homeStatus, homeBattery, homeVolume, homeLatency;
    private TextView result, resolutionHint, measurement, profileHint, pointerSpeedLabel, monitorHint, videoHint, volumeLabel, discovery, connectionStatus, routeHint;
    private Button connect, manualToggle, usbSettings, adb, forget, startSecond, mute, qualityButton, rateButton;
    private EditText pin, address, port;
    private SeekBar slider;
    private AlertDialog verifyDialog;
    private WifiManager.MulticastLock broadcasts;
    private final BroadcastReceiver batteryChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { readPhoneBattery(intent); }
    };

    @Override protected void onCreate(Bundle restored) {
        super.onCreate(restored);
        preferences = getSharedPreferences("pc-link", MODE_PRIVATE);
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
        });
        desktop.pointerSensitivity(pointerSpeed);
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
        rememberFields();
        desktop.endGesture();
        if (desktop.getParent() instanceof FrameLayout) ((FrameLayout) desktop.getParent()).removeView(desktop);
        buildUi();
    }

    private void buildUi() {
        Configuration current = getResources().getConfiguration();
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
            return insets;
        });

        LinearLayout head = horizontal();
        head.setBackgroundColor(HEADER);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(9), dp(17), dp(9));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.proyector_icon);
        head.addView(mark, lp(dp(35), dp(35)));
        TextView brand = title("Control de PC", 19);
        LinearLayout.LayoutParams brandParams = weighted(1f);
        brandParams.setMarginStart(dp(10));
        head.addView(brand, brandParams);
        phoneLabel = text("", 11, MUTED, true);
        phoneLabel.setPadding(dp(8), 0, dp(8), 0);
        head.addView(phoneLabel);
        badge = pill("Sin conexión", MUTED, WELL);
        badge.setOnClickListener(view -> showPage(CONNECTION, true));
        head.addView(badge);
        shell.addView(head, lp(-1, -2));

        LinearLayout body = wide ? horizontal() : vertical();
        shell.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        nav = wide ? vertical() : horizontal();
        nav.setPadding(dp(6), dp(7), dp(6), dp(7));
        nav.setBackgroundColor(HEADER);
        navigation = new NavItem[TAB_NAMES.length];
        for (int i = 0; i < navigation.length; i++) {
            final int destination = i;
            navigation[i] = new NavItem(TAB_NAMES[i], TAB_ICONS[i]);
            navigation[i].setOnClickListener(view -> showPage(destination, true));
            if (wide) add(nav, navigation[i], 3);
            else nav.addView(navigation[i], weighted(1f));
        }
        if (wide) body.addView(nav, lp(dp(105), -1));
        pageHost = new FrameLayout(this);
        if (wide) body.addView(pageHost, new LinearLayout.LayoutParams(0, -1, 1));
        else {
            body.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
            body.addView(nav, lp(-1, -2));
        }

        pages = new View[]{homePage(), remotePage(), monitorPage(), controlsPage(), connectionPage()};
        for (View item : pages) {
            item.setVisibility(View.GONE);
            pageHost.addView(item, new FrameLayout.LayoutParams(-1, -1));
        }
        viewerHost = new FrameLayout(this);
        viewerHost.setBackgroundColor(Color.BLACK);
        viewerHost.addView(desktop, new FrameLayout.LayoutParams(-1, -1));
        videoHint = text("Preparando imagen…", 15, MUTED, true);
        videoHint.setGravity(Gravity.CENTER);
        viewerHost.addView(videoHint, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        root.addView(viewerHost, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        showPage(page, false);
        setViewerVisibility();
        refresh();
    }

    private View homePage() {
        LinearLayout content = page("Inicio");
        LinearLayout device = card(false);
        device.setBackground(gradientSurface(new int[]{Color.rgb(31, 62, 83), Color.rgb(22, 37, 54)}, 20, BORDER));
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(iconBadge("screen"), lp(dp(48), dp(48)));
        LinearLayout details = vertical();
        LinearLayout.LayoutParams detailsLayout = weighted(1f);
        detailsLayout.setMarginStart(dp(12));
        heading.addView(details, detailsLayout);
        homeComputer = title("Tu computadora", 22);
        homeStatus = text("", 14, MUTED, false);
        details.addView(homeComputer);
        add(details, homeStatus, 4);
        device.addView(heading);
        LinearLayout heroActions = horizontal();
        Button viewDesktop = button("Ver escritorio", true);
        viewDesktop.setOnClickListener(view -> openViewer(Wire.DESKTOP, false));
        heroActions.addView(viewDesktop, weighted(1f));
        Button manage = button("Conexión", false);
        LinearLayout.LayoutParams manageLayout = weighted(1f);
        manageLayout.setMarginStart(dp(8));
        heroActions.addView(manage, manageLayout);
        manage.setOnClickListener(view -> showPage(CONNECTION, true));
        add(device, heroActions, 15);
        add(content, device, 13);

        add(content, sectionHeading("Estado en tiempo real", "La conexión se pausa cuando no estás viendo la pantalla."), 19);
        homeBattery = title("—", 17);
        homeVolume = title("—", 17);
        homeLatency = title("—", 17);
        add(content, tilePair(metricCard("battery", "Batería de Windows", homeBattery),
                metricCard("volume", "Volumen", homeVolume)), 9);
        add(content, metricCard("latency", "Respuesta de la conexión", homeLatency), 8);

        add(content, sectionHeading("Acciones rápidas", "Lo más usado, a un toque."), 19);
        add(content, tilePair(tile("screen", "Ver escritorio", "Toca directamente la pantalla de Windows", () -> openViewer(Wire.DESKTOP, false)),
                tile("touch", "Panel táctil", "Mueve el cursor como con un touchpad", () -> openViewer(Wire.DESKTOP, true))), 10);
        add(content, tilePair(tile("dual", "Segundo monitor", "Abre una pantalla adicional de Windows", () -> showPage(MONITOR, true)),
                tile("sliders", "Controles", "Audio, atajos y energía", () -> showPage(CONTROLS, true))), 9);
        return scroll(content);
    }

    private View remotePage() {
        LinearLayout content = page("Escritorio remoto");
        profileTiles.clear();
        add(content, columns(tile("screen", "Control directo", "Toca, selecciona y arrastra en el escritorio.", () -> openViewer(Wire.DESKTOP, false)),
                tile("touch", "Panel táctil", "Desliza para mover el cursor. Toca para hacer clic.", () -> openViewer(Wire.DESKTOP, true))), 13);
        LinearLayout video = card(false);
        video.addView(title("Calidad de imagen", 17));
        adaptiveSwitch = new Switch(this);
        adaptiveSwitch.setText("Ajuste automático");
        adaptiveSwitch.setTextColor(TEXT);
        adaptiveSwitch.setMinHeight(dp(47));
        adaptiveSwitch.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACCENT, MUTED}));
        adaptiveSwitch.setChecked(adaptive);
        adaptiveSwitch.setOnCheckedChangeListener((view, enabled) -> { adaptive = enabled; saveVideoSettings(); });
        add(video, adaptiveSwitch, 4);
        qualityButton = button("", false);
        qualityButton.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("Resolución máxima")
                .setSingleChoiceItems(Wire.RESOLUTIONS, quality, (dialog, selected) -> {
                    quality = selected;
                    saveVideoSettings();
                    dialog.dismiss();
                }).setNegativeButton("Cancelar", null).show());
        rateButton = button("", false);
        rateButton.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("Fluidez máxima")
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
        add(profiles, tilePair(profileButton("Ahorro", "540p · 20 FPS", 1, 1, true),
                profileButton("Equilibrado", "720p · 30 FPS", 2, 2, true)), 8);
        add(profiles, tilePair(profileButton("Fluido", "720p · 60 FPS", 2, 4, true),
                profileButton("Full HD", "1080p · 30 FPS", 3, 2, false)), 8);

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
        startSecond.setOnClickListener(view -> openViewer(Wire.SECOND_DISPLAY, false));
        add(card, startSecond, 12);
        LinearLayout help = card(false);
        help.addView(title("Preparar el escritorio extendido", 17));
        add(help, text("En Windows, abre Configuración de pantallas y selecciona Extender cuando el sistema detecte la pantalla adicional.", 14, MUTED, false), 8);
        add(help, text("Para crear una pantalla virtual, Windows necesita un controlador de pantalla firmado. Este paquete permite usar una pantalla que ya esté activa.", 14, MUTED, false), 8);
        add(content, columns(card, help), 13);
        return scroll(content);
    }

    private View controlsPage() {
        powerButtons.clear();
        audioButtons.clear();
        shortcutButtons.clear();
        LinearLayout content = page("Controles de la PC");
        LinearLayout audio = card(false);
        audio.addView(title("Volumen", 19));
        volumeLabel = title("—", 30);
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

        LinearLayout power = card(false);
        power.addView(title("Energía y sesión", 19));
        add(power, tilePair(control("Bloquear", Wire.LOCK, null, powerButtons),
                control("Suspender", Wire.SLEEP, "Windows quedará en reposo y la conexión se interrumpirá.", powerButtons)), 10);
        add(power, tilePair(control("Reiniciar", Wire.RESTART, "Guarda el trabajo abierto antes de reiniciar.", powerButtons),
                control("Apagar PC", Wire.SHUTDOWN, "Guarda el trabajo abierto antes de apagar.", powerButtons)), 8);
        LinearLayout shortcuts = card(false);
        shortcuts.addView(title("Atajos y multimedia", 19));
        add(shortcuts, text("Atajos permitidos de Windows y controles del reproductor.", 13, MUTED, false), 4);
        add(shortcuts, tilePair(shortcut("Escritorio", 1), shortcut("Cambiar ventana", 2)), 8);
        add(shortcuts, shortcut("Administrador de tareas", 3), 7);
        add(shortcuts, tilePair(shortcut("Reproducir / pausar", 4), shortcut("Anterior", 5)), 7);
        add(shortcuts, shortcut("Siguiente", 6), 7);
        add(content, columns(audio, power), 13);
        add(content, shortcuts, 10);
        result = text("", 14, MUTED, false);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, result, 12);
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
            modeButtons[i].setTextSize(12);
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
            new AlertDialog.Builder(this).setTitle("USB directo con ADB")
                    .setMessage("Si ya configuraste ADB en Windows y autorizaste tu PC, ejecuta adb reverse tcp:5050 tcp:5050. Después escribe el código y pulsa Conectar.")
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
            new AlertDialog.Builder(this).setTitle("Elige tu computadora")
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
            modeButtons[i].setTextColor(selected ? ACCENT : TEXT);
            modeButtons[i].setBackground(tappable(selected ? ACCENT_SURFACE : WELL, 10, selected ? ACCENT : BORDER));
            modeButtons[i].setSelected(selected);
        }
        routeHint.setText(mode == ReceiverLink.Mode.LAN ? "Conecta Android y Windows a la misma red."
                : mode == ReceiverLink.Mode.USB ? "Usa un cable de datos y activa Compartir Internet por USB."
                : "Conecta ambos equipos a Tailscale e introduce la IP que figura en el emisor.");
        List<ReceiverLink.Endpoint> candidates = discoveredDevices();
        discovery.setVisibility(mode == ReceiverLink.Mode.VPN ? View.GONE : View.VISIBLE);
        discovery.setText(candidates.isEmpty() ? "Buscando la PC…" : candidates.size() == 1
                ? candidates.get(0).computer + "  ·  " + candidates.get(0).address()
                : candidates.size() + " PC disponibles");
        boolean busy = state != ReceiverLink.State.IDLE;
        boolean usb = mode == ReceiverLink.Mode.USB;
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
        if (phoneLabel != null && phoneBattery >= 0) {
            phoneLabel.setText("Android " + phoneBattery + " %");
            phoneLabel.setContentDescription("Batería de Android " + phoneBattery + " por ciento");
        }
    }

    private void refresh() {
        if (homeStatus == null) return;
        boolean connected = state == ReceiverLink.State.CONNECTED;
        String route = linkedMode == ReceiverLink.Mode.USB ? "USB" : linkedMode == ReceiverLink.Mode.VPN ? "Tailscale" : "red local";
        badge.setText(connected ? "Conectado" : state == ReceiverLink.State.IDLE ? "Sin conexión" : "Conectando");
        badge.setTextColor(connected ? ACCENT : MUTED);
        badge.setBackground(tappable(connected ? ACCENT_SURFACE : WELL, 16, BORDER));
        phoneLabel.setText(phoneBattery < 0 ? "" : "Android " + phoneBattery + " %");
        homeComputer.setText(computer.isEmpty() ? "Tu computadora" : computer);
        homeStatus.setText(connected ? "Cifrado activo · " + route + " · " + networkQuality() : status);
        homeBattery.setText(!connected ? "—" : battery < 0 ? "N/D" : battery + " %" + (charging ? "  ↗" : ""));
        homeBattery.setTextColor(battery >= 0 && battery <= 15 ? WARNING : TEXT);
        homeVolume.setText(volume < 0 || !connected ? "—" : volume + " %" + (muted ? "  ·  Silencio" : ""));
        homeVolume.setTextColor(muted ? WARNING : TEXT);
        homeLatency.setText(!connected || latency < 0 ? "—" : latency + " ms");
        homeLatency.setTextColor(latency >= 0 && latency > 180 ? WARNING : TEXT);
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
        monitorHint.setText(!connected ? "Conecta tu PC para detectar sus pantallas."
                : secondary.isEmpty() ? "Windows no detecta una pantalla adicional activa."
                : secondary + "  ·  " + monitorCount + " pantallas detectadas");
        startSecond.setEnabled(connected && !secondary.isEmpty());
        startSecond.setAlpha(startSecond.isEnabled() ? 1f : .52f);
        refreshQuality();
        refreshConnection();
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
        for (View tile : profileTiles) {
            boolean selected = currentProfile.equals(tile.getTag());
            if (tile.isSelected() != selected) {
                tile.setSelected(selected);
                tile.setBackground(tappable(selected ? ACCENT_SURFACE : PANEL, 17, selected ? ACCENT : BORDER));
            }
        }
    }

    private String selectedProfile() {
        if (quality == 1 && frameRate == 1 && adaptive) return "Ahorro";
        if (quality == 2 && frameRate == 2 && adaptive) return "Equilibrado";
        if (quality == 2 && frameRate == 4 && adaptive) return "Fluido";
        if (quality == 3 && frameRate == 2 && !adaptive) return "Full HD";
        return "Personalizado";
    }

    private String networkQuality() {
        if (latency < 0) return "midiendo respuesta";
        if (latency <= 80) return "respuesta rápida";
        if (latency <= 180) return "enlace estable";
        return "respuesta lenta";
    }

    private void saveVideoSettings() {
        preferences.edit().putInt("quality", quality).putInt("fps", frameRate).putBoolean("adaptive", adaptive).apply();
        refreshQuality();
    }

    private void showPage(int index, boolean animate) {
        if (page == CONNECTION && index != CONNECTION) { rememberFields(); closeKeyboard(); }
        for (int i = 0; i < pages.length; i++) {
            pages[i].animate().cancel();
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            navigation[i].active(i == index);
        }
        page = index;
        View current = pages[page];
        current.setAlpha(animate ? .6f : 1f);
        current.setTranslationY(animate ? dp(6) : 0f);
        if (animate) current.animate().alpha(1f).translationY(0f).setDuration(210)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void openViewer(int desired, boolean panelMode) {
        if (state != ReceiverLink.State.CONNECTED) {
            showPage(CONNECTION, true);
            showConnectionMessage("Conecta tu PC para abrir el escritorio.", false);
            return;
        }
        viewing = desired;
        desktop.panelMode(panelMode);
        closeKeyboard();
        configureStream();
        setViewerVisibility();
        if (Build.VERSION.SDK_INT <= 28 || android.animation.ValueAnimator.areAnimatorsEnabled()) {
            viewerHost.setAlpha(.65f);
            viewerHost.animate().alpha(1f).setDuration(140).start();
        }
    }

    private void closeViewer() {
        desktop.endGesture();
        viewing = Wire.PAUSED;
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
        shell.setVisibility(active ? View.GONE : View.VISIBLE);
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (active) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (active) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        root.requestApplyInsets();
    }

    private void clearFrame() {
        gotFrame = false;
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
                    frameCount = 0;
                    meterStart = now;
                }
            } else frame.image.recycle();
        }
        drawScheduled.set(false);
        if (latestFrame.get() != null && drawScheduled.compareAndSet(false, true)) main.post(this::drawLatest);
    }

    private void showKeyboard() {
        EditText input = field("Texto para Windows", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Escribir en la PC").setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Enviar", (window, which) -> {
                    String value = input.getText().toString();
                    if (!value.isEmpty() && gotFrame) link.text(value, visibleSequence);
                    setViewerVisibility();
                }).create();
        input.requestFocus();
        dialog.setOnDismissListener(window -> setViewerVisibility());
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
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
        if (viewing != Wire.PAUSED) { closeViewer(); return; }
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
                volume = battery = -1;
                muted = false;
                secondary = "";
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
            verifyDialog = new AlertDialog.Builder(MainActivity.this).setTitle("Verificar PC").setView(display)
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

        @Override public void screens(int count, String target) {
            monitorCount = count;
            secondary = target;
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

        @Override public void latency(long delay) { latency = delay; refresh(); }
        @Override public void frame(Bitmap image, int sourceRevision, int sequence) { queueFrame(image, sourceRevision, sequence); }
    }

    private Button control(String label, int command, String confirmation, List<View> group) {
        Button button = button(label, false);
        if (command == Wire.SHUTDOWN) button.setBackground(tappable(DANGER, 11, BORDER));
        button.setOnClickListener(view -> {
            if (confirmation == null) execute(command);
            else new AlertDialog.Builder(this).setTitle(button.getText()).setMessage(confirmation)
                    .setNegativeButton("Cancelar", null).setPositiveButton("Confirmar", (dialog, which) -> execute(command)).show();
        });
        group.add(button);
        return button;
    }

    private Button shortcut(String label, int shortcut) {
        Button action = button(label, false);
        action.setContentDescription("Enviar atajo de Windows: " + label);
        action.setOnClickListener(view -> execute(Wire.SHORTCUT, shortcut));
        shortcutButtons.add(action);
        return action;
    }

    private View profileButton(String name, String details, int resolutionIndex, int fpsIndex, boolean auto) {
        LinearLayout option = card(true);
        option.setMinimumHeight(dp(76));
        option.addView(title(name, 15));
        add(option, text(details, 12, MUTED, false), 3);
        option.setContentDescription(name + ", " + details);
        option.setTag(name);
        profileTiles.add(option);
        option.setOnClickListener(view -> {
            quality = resolutionIndex;
            frameRate = fpsIndex;
            adaptive = auto;
            if (adaptiveSwitch != null && adaptiveSwitch.isChecked() != auto) adaptiveSwitch.setChecked(auto);
            saveVideoSettings();
            option.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        });
        return option;
    }

    private View metricCard(String icon, String label, TextView value) {
        LinearLayout metric = card(false);
        metric.setMinimumHeight(dp(93));
        metric.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(iconBadge(icon), lp(dp(27), dp(27)));
        TextView caption = text(label, 11, MUTED, true);
        LinearLayout.LayoutParams captionParams = weighted(1f);
        captionParams.setMarginStart(dp(7));
        heading.addView(caption, captionParams);
        metric.addView(heading);
        add(metric, value, 7);
        metric.setContentDescription(label);
        return metric;
    }

    private LinearLayout sectionHeading(String heading, String detail) {
        LinearLayout block = vertical();
        block.addView(title(heading, 17));
        add(block, text(detail, 12, MUTED, false), 3);
        return block;
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
        column.setPadding(dp(wide ? 22 : 15), dp(17), dp(wide ? 22 : 15), dp(21));
        column.addView(title(heading, 27));
        String detail;
        if ("Inicio".equals(heading)) detail = "Tu PC, el estado del enlace y tus acciones principales.";
        else if ("Escritorio remoto".equals(heading)) detail = "Ajusta la imagen y el control táctil a tu conexión.";
        else if ("Segundo monitor".equals(heading)) detail = "Abre una pantalla extendida que Windows ya reconozca.";
        else if ("Controles de la PC".equals(heading)) detail = "Audio, accesos rápidos, bloqueo y energía.";
        else if ("Conexión".equals(heading)) detail = "Vincula tus equipos con una conexión privada cifrada.";
        else detail = "Control remoto de Windows";
        add(column, text(detail, 13, MUTED, false), 4);
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

    private View tile(String icon, String label, String detail, Runnable action) {
        LinearLayout card = card(true);
        card.setMinimumHeight(dp(124));
        card.addView(iconBadge(icon), lp(dp(37), dp(37)));
        add(card, title(label, 16), 8);
        add(card, text(detail, 12, MUTED, false), 4);
        card.setFocusable(true);
        card.setContentDescription(label + ". " + detail);
        card.setOnClickListener(view -> action.run());
        return card;
    }

    private LinearLayout card(boolean action) {
        LinearLayout view = vertical();
        view.setPadding(dp(14), dp(13), dp(14), dp(13));
        view.setBackground(action ? tappable(PANEL, 17, BORDER) : surface(PANEL, 17, BORDER));
        return view;
    }

    private GradientDrawable gradientSurface(int[] colors, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
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
        result.setMinHeight(dp(45));
        result.setMinimumHeight(dp(45));
        result.setMinimumWidth(0);
        result.setMinWidth(0);
        result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        result.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{MUTED, primary ? BG : TEXT}));
        result.setStateListAnimator(null);
        result.setBackground(tappable(primary ? ACCENT : WELL, 11, primary ? ACCENT : BORDER));
        return result;
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
        return new RippleDrawable(ColorStateList.valueOf(0x31b4f7e9), surface(color, radius, stroke), surface(Color.WHITE, radius, 0));
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
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setMinimumHeight(dp(54));
            setPadding(dp(1), dp(5), dp(1), dp(5));
            setFocusable(true);
            icon = new Icon(MainActivity.this, symbol, MUTED);
            addView(icon, lp(dp(21), dp(21)));
            caption = text(label, 10, MUTED, true);
            caption.setGravity(Gravity.CENTER);
            addView(caption, lp(-1, -2));
            setContentDescription(label);
        }

        void active(boolean selected) {
            setSelected(selected);
            icon.tint(selected ? ACCENT : MUTED);
            caption.setTextColor(selected ? ACCENT : MUTED);
            setBackground(tappable(selected ? ACCENT_SURFACE : HEADER, 12, 0));
        }
    }

    private static final class VideoFrame {
        final Bitmap image;
        final int revision;
        final int sequence;
        VideoFrame(Bitmap image, int revision, int sequence) { this.image = image; this.revision = revision; this.sequence = sequence; }
    }
}
