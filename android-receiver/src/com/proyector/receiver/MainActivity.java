package com.proyector.receiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputFilter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int DEFAULT_PORT = 5050;
    private static final int DISCOVERY_PORT = 5051;
    private static final int MAX_FRAME_BYTES = 12 * 1024 * 1024;
    private static final int COLOR_BG = Color.rgb(7, 16, 35);
    private static final int COLOR_CARD = Color.rgb(20, 39, 70);
    private static final int COLOR_CARD_RAISED = Color.rgb(32, 60, 103);
    private static final int COLOR_TEXT = Color.rgb(248, 250, 255);
    private static final int COLOR_MUTED = Color.rgb(177, 198, 229);
    private static final int COLOR_ACCENT = Color.rgb(60, 178, 255);
    private static final int COLOR_BORDER = Color.rgb(52, 85, 131);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private EditText addressInput;
    private EditText portInput;
    private EditText pinInput;
    private TextView statusText;
    private TextView discoveredText;
    private Button discoveryButton;
    private LinearLayout manualSection;
    private Button quickConnectButton;
    private LinearLayout homeScreen;
    private ScrollView homeScroll;
    private LinearLayout bottomNav;
    private LinearLayout appUi;
    private FrameLayout pageHost;
    private View[] pages;
    private int pageIndex = -1;
    private boolean tabletLayout;
    private int statusSequence;
    private TextView deviceStatus;
    private TextView commandResultText;
    private TextView batteryText;
    private TextView pcBatteryText;
    private TextView networkModeHint;
    private TextView volumeLevelText;
    private SeekBar volumeSeekBar;
    private boolean updatingVolumeFromPc;
    private boolean vpnMode;
    private boolean batteryReceiverRegistered;
    private FrameLayout viewerScreen;
    private ImageView imageView;
    private final AtomicBoolean framePending = new AtomicBoolean(false);
    private volatile int frameWidth;
    private volatile int frameHeight;
    private boolean touchpadMode;
    private boolean gestureMoved;
    private boolean longPressed;
    private boolean dragging;
    private boolean twoFingerScroll;
    private boolean secondTapHold;
    private float touchStartX;
    private float touchStartY;
    private float lastX;
    private float lastY;
    private float scrollY;
    private int lastDirectX;
    private int lastDirectY;
    private long lastPointerDispatch;
    private float pendingRelativeX;
    private float pendingRelativeY;
    private long lastTapAt;
    private Runnable longPressTask;
    private volatile boolean alive = true;
    private volatile boolean discoveryEnabled = true;
    private volatile String discoveredHost;
    private volatile int discoveredPort = DEFAULT_PORT;
    private volatile String manualHost;
    private volatile int manualPort = DEFAULT_PORT;
    private volatile Socket activeSocket;
    private volatile DataOutputStream activeOutput;
    private volatile String activeHost;
    private volatile String pairingPin = "";
    private volatile boolean streamConnected;
    private volatile boolean pairingReady;
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private WifiManager.MulticastLock multicastLock;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateBattery(intent); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        buildUi();
        startDiscovery();
        startConnectionLoop();
    }

    @Override protected void onStart() {
        super.onStart();
        if (batteryReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky;
        if (Build.VERSION.SDK_INT >= 33) {
            sticky = registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            sticky = registerReceiver(batteryReceiver, filter);
        }
        batteryReceiverRegistered = true;
        updateBattery(sticky);
    }

    @Override protected void onStop() {
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver);
            batteryReceiverRegistered = false;
        }
        super.onStop();
    }

    private void updateBattery(Intent intent) {
        if (intent == null || batteryText == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int percent = Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
        batteryText.setText("TEL " + percent + "%");
        batteryText.setTextColor(percent <= 15 ? Color.rgb(255, 176, 132) : COLOR_TEXT);
        batteryText.setContentDescription("Batería del teléfono: " + percent + "%");
    }

    private void buildUi() {
        tabletLayout = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(COLOR_BG);
        appUi = new LinearLayout(this);
        appUi.setOrientation(LinearLayout.VERTICAL);
        appUi.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(7, 18, 39), Color.rgb(13, 37, 74), Color.rgb(6, 17, 36)}));
        frame.addView(appUi, new FrameLayout.LayoutParams(-1, -1));
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            appUi.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(8), dp(20), dp(8));
        header.setBackgroundColor(Color.rgb(10, 27, 54));
        header.addView(buildMark(), params(dp(34), dp(34)));
        TextView appTitle = text("Control de PC", 20, COLOR_TEXT, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.leftMargin = dp(12);
        header.addView(appTitle, titleParams);
        batteryText = text("TEL --%", 10, COLOR_TEXT, true);
        batteryText.setPadding(dp(9), dp(7), dp(9), dp(7));
        batteryText.setBackground(rounded(Color.rgb(22, 57, 101), dp(12), COLOR_BORDER, dp(1)));
        batteryText.setContentDescription("Batería del teléfono");
        header.addView(batteryText);
        TextView local = text("LAN · VPN", 10, COLOR_ACCENT, true);
        local.setPadding(dp(10), dp(7), dp(10), dp(7));
        local.setBackground(rounded(Color.rgb(22, 57, 101), dp(12), COLOR_BORDER, dp(1)));
        header.addView(local);
        appUi.addView(header, params(-1, dp(60)));

        pageHost = new FrameLayout(this);
        appUi.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        homeScreen = pageContent();
        homeScroll = scrollPage(homeScreen);
        pages = new View[]{homeScroll, scrollPage(buildRemotePage()),
                scrollPage(buildControlsPage()), scrollPage(buildConnectionPage()),
                scrollPage(buildSecondMonitorPage())};
        for (View page : pages) {
            page.setVisibility(View.GONE);
            pageHost.addView(page, new FrameLayout.LayoutParams(-1, -1));
        }
        buildHomePage();

        statusText = text("", 12, COLOR_TEXT, false);
        statusText.setPadding(dp(18), dp(10), dp(18), dp(10));
        statusText.setBackgroundColor(Color.rgb(20, 57, 97));
        statusText.setVisibility(View.GONE);
        appUi.addView(statusText, params(-1, -2));
        buildBottomNav();
        appUi.addView(bottomNav, params(-1, dp(68)));

        buildViewer(frame);
        setContentView(frame);
        showPage(0);
    }

    private LinearLayout pageContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        if (tabletLayout) content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(tabletLayout ? 28 : 18), dp(22),
                dp(tabletLayout ? 28 : 18), dp(28));
        return content;
    }

    private ScrollView scrollPage(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        return scroll;
    }

    private void addSpaced(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams p = params(-1, -2);
        p.topMargin = dp(top);
        parent.addView(child, p);
    }

    private void buildHomePage() {
        LinearLayout deviceCard = card();
        LinearLayout deviceRow = new LinearLayout(this);
        deviceRow.setGravity(Gravity.CENTER_VERTICAL);
        deviceRow.addView(new SymbolView("monitor", COLOR_ACCENT), params(dp(38), dp(38)));
        LinearLayout deviceInfo = new LinearLayout(this);
        deviceInfo.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, -2, 1);
        infoParams.leftMargin = dp(14);
        deviceRow.addView(deviceInfo, infoParams);
        deviceInfo.addView(text("TU COMPUTADORA", 11, COLOR_ACCENT, true));
        deviceInfo.addView(text("Windows", 22, COLOR_TEXT, true));
        deviceCard.addView(deviceRow);
        deviceStatus = text("●  Buscando PC en la red local", 13, COLOR_MUTED, false);
        addSpaced(deviceCard, deviceStatus, 12);
        pcBatteryText = text("Batería de Windows · esperando conexión", 12, COLOR_MUTED, false);
        addSpaced(deviceCard, pcBatteryText, 8);
        Button openConnection = button("Conectar o cambiar equipo", COLOR_CARD_RAISED, COLOR_TEXT);
        addSpaced(deviceCard, openConnection, 14);
        openConnection.setOnClickListener(view -> showPage(3));

        LinearLayout monitorInfo = card();
        monitorInfo.addView(new SymbolView("monitor", COLOR_ACCENT), params(dp(30), dp(30)));
        TextView secondMonitorTitle = text("Usar teléfono como segundo monitor", 15, COLOR_TEXT, true);
        addSpaced(monitorInfo, secondMonitorTitle, 7);
        addSpaced(monitorInfo, text("Opción disponible cuando Windows tenga instalado el controlador de pantalla virtual.", 12, COLOR_MUTED, false), 4);
        Button openSecondMonitor = button("Abrir opción de segundo monitor", COLOR_CARD_RAISED, COLOR_TEXT);
        addSpaced(monitorInfo, openSecondMonitor, 12);
        openSecondMonitor.setOnClickListener(view -> showPage(4));
        addPressFeedback(monitorInfo);

        GridLayout shortcuts = new GridLayout(this);
        shortcuts.setColumnCount(2);
        shortcuts.addView(quickTile("monitor", "Ver escritorio", "Control directo", () -> showViewer(false)), shortcutParams(0));
        shortcuts.addView(quickTile("touch", "Panel táctil", "Mueve el cursor", () -> showViewer(true)), shortcutParams(1));
        shortcuts.addView(quickTile("power", "Energía", "Bloquear y apagar", () -> showPage(2)), shortcutParams(0));
        shortcuts.addView(quickTile("connect", "Conexión", "Wi-Fi o USB", () -> showPage(3)), shortcutParams(1));

        if (tabletLayout) {
            LinearLayout columns = new LinearLayout(this);
            columns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(deviceCard);
            addSpaced(left, monitorInfo, 16);
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.addView(text("ACCESOS RÁPIDOS", 12, COLOR_ACCENT, true));
            addSpaced(right, shortcuts, 10);
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -2, 1);
            leftParams.rightMargin = dp(10);
            columns.addView(left, leftParams);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1);
            rightParams.leftMargin = dp(10);
            columns.addView(right, rightParams);
            homeScreen.addView(columns);
        } else {
            homeScreen.addView(deviceCard);
            addSpaced(homeScreen, text("ACCESOS RÁPIDOS", 12, COLOR_ACCENT, true), 22);
            addSpaced(homeScreen, shortcuts, 10);
            addSpaced(homeScreen, monitorInfo, 18);
        }
    }

    private void showSecondMonitorInfo() {
        new AlertDialog.Builder(this)
                .setTitle("Segundo monitor")
                .setMessage("Para que Windows vea el teléfono como una pantalla adicional hace falta un controlador de pantalla virtual firmado. La conexión actual muestra y controla el escritorio, pero no puede crear ese monitor por sí sola.")
                .setPositiveButton("Entendido", null)
                .show();
    }

    private LinearLayout buildSecondMonitorPage() {
        LinearLayout page = pageContent();
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(text("Segundo monitor", 26, COLOR_TEXT, true));
        addSpaced(page, text("Funciona separado del escritorio remoto y del panel táctil.", 13, COLOR_MUTED, false), 5);

        LinearLayout modeCard = card();
        modeCard.addView(new SymbolView("monitor", COLOR_ACCENT), params(dp(38), dp(38)));
        addSpaced(modeCard, text("Usar este teléfono como otra pantalla", 18, COLOR_TEXT, true), 10);
        addSpaced(modeCard, text("Esta opción necesita un controlador de pantalla virtual para que Windows registre el teléfono como Monitor 2.", 13, COLOR_MUTED, false), 7);
        Button requirements = button("Ver requisitos", COLOR_CARD_RAISED, COLOR_TEXT);
        addSpaced(modeCard, requirements, 16);
        requirements.setOnClickListener(view -> showSecondMonitorInfo());
        addPressFeedback(modeCard);
        addSpaced(page, modeCard, 24);

        LinearLayout steps = card();
        steps.addView(text("CÓMO QUEDARÁ", 11, COLOR_ACCENT, true));
        addSpaced(steps, text("1. Windows instala un monitor virtual.\n2. La APK recibe la imagen de esa pantalla.\n3. Puedes mover ventanas a ella desde Configuración de pantalla.", 13, COLOR_MUTED, false), 10);
        addSpaced(page, steps, 14);
        return page;
    }

    private GridLayout.LayoutParams shortcutParams(int column) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(column, 1f));
        p.width = 0;
        p.height = dp(126);
        p.setMargins(column == 0 ? 0 : dp(6), dp(5),
                column == 0 ? dp(6) : 0, dp(5));
        return p;
    }

    private View quickTile(String symbol, String title, String detail, Runnable action) {
        LinearLayout tile = card();
        tile.setPadding(dp(15), dp(14), dp(12), dp(12));
        tile.addView(new SymbolView(symbol, COLOR_ACCENT), params(dp(29), dp(29)));
        addSpaced(tile, text(title, 15, COLOR_TEXT, true), 9);
        addSpaced(tile, text(detail, 11, COLOR_MUTED, false), 3);
        tile.setOnClickListener(view -> action.run());
        addPressFeedback(tile);
        return tile;
    }

    private LinearLayout buildRemotePage() {
        LinearLayout page = pageContent();
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(text("Escritorio remoto", 26, COLOR_TEXT, true));
        addSpaced(page, text("Elige cómo quieres controlar Windows.", 13, COLOR_MUTED, false), 5);
        View direct = remoteChoice("monitor", "Ver escritorio", "Toca directamente, haz clic y arrastra ventanas.", false);
        View touchpad = remoteChoice("touch", "Panel táctil", "Desliza para mover el cursor y toca para hacer clic.", true);
        LinearLayout help = card();
        help.addView(text("GESTOS", 11, COLOR_ACCENT, true));
        addGestureRow(help, "1 dedo", "Clic y arrastrar");
        addGestureRow(help, "Mantener", "Clic derecho");
        addGestureRow(help, "2 dedos", "Desplazamiento");
        addGestureRow(help, "3 dedos", "Abrir teclado");
        if (tabletLayout) {
            LinearLayout columns = new LinearLayout(this);
            columns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout choices = new LinearLayout(this);
            choices.setOrientation(LinearLayout.VERTICAL);
            choices.addView(direct);
            addSpaced(choices, touchpad, 12);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
            left.rightMargin = dp(10);
            columns.addView(choices, left);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
            right.leftMargin = dp(10);
            columns.addView(help, right);
            addSpaced(page, columns, 23);
        } else {
            addSpaced(page, direct, 23);
            addSpaced(page, touchpad, 12);
            addSpaced(page, help, 20);
        }
        return page;
    }

    private void addGestureRow(LinearLayout parent, String gesture, String action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(gesture, 12, COLOR_ACCENT, true), params(dp(94), -2));
        row.addView(text(action, 12, COLOR_MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        addSpaced(parent, row, 8);
    }

    private View remoteChoice(String symbol, String title, String description, boolean panel) {
        LinearLayout tile = card();
        tile.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new SymbolView(symbol, COLOR_ACCENT), params(dp(42), dp(42)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, -2, 1);
        wordParams.leftMargin = dp(16);
        row.addView(words, wordParams);
        words.addView(text(title, 19, COLOR_TEXT, true));
        addSpaced(words, text(description, 12, COLOR_MUTED, false), 5);
        row.addView(text("›", 26, COLOR_ACCENT, false));
        tile.addView(row);
        tile.setMinimumHeight(dp(112));
        tile.setOnClickListener(view -> showViewer(panel));
        addPressFeedback(tile);
        return tile;
    }

    private LinearLayout buildControlsPage() {
        LinearLayout page = pageContent();
        page.addView(text("Controles rápidos", 26, COLOR_TEXT, true));
        addSpaced(page, text("Acciones directas para tu computadora.", 13, COLOR_MUTED, false), 5);

        LinearLayout power = card();
        power.addView(text("ENERGÍA Y SESIÓN", 12, COLOR_ACCENT, true));
        GridLayout powerGrid = new GridLayout(this);
        powerGrid.setColumnCount(2);
        powerGrid.addView(actionButton("lock", "Bloquear", "La sesión se bloqueará; tus programas seguirán abiertos", 10), gridParams(0));
        powerGrid.addView(actionButton("power", "Suspender", "La PC entrará en suspensión", 1), gridParams(1));
        powerGrid.addView(actionButton("restart", "Reiniciar", "La PC se reiniciará", 11), gridParams(0));
        powerGrid.addView(actionButton("power", "Apagar PC", "La PC se apagará", 2), gridParams(1));
        addSpaced(power, powerGrid, 12);

        LinearLayout audio = card();
        audio.addView(text("AUDIO", 12, COLOR_ACCENT, true));
        volumeLevelText = text("Volumen de Windows · esperando conexión", 12, COLOR_MUTED, false);
        addSpaced(audio, volumeLevelText, 9);
        volumeSeekBar = new SeekBar(this);
        volumeSeekBar.setMax(100);
        volumeSeekBar.setEnabled(false);
        volumeSeekBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        volumeSeekBar.setThumbTintList(ColorStateList.valueOf(COLOR_ACCENT));
        audio.addView(volumeSeekBar, params(-1, dp(38)));
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !updatingVolumeFromPc && volumeLevelText != null)
                    volumeLevelText.setText("Volumen de Windows · " + progress + "% · suelta para aplicar");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (!updatingVolumeFromPc) sendPacket(16, seekBar.getProgress());
            }
        });
        LinearLayout audioRow = new LinearLayout(this);
        audioRow.setOrientation(LinearLayout.HORIZONTAL);
        View[] actions = {
                actionButton("volume", "Bajar", null, 12),
                actionButton("mute", "Silenciar", null, 3),
                actionButton("volume", "Subir", null, 4)
        };
        for (int i = 0; i < actions.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(78), 1);
            p.setMargins(i == 0 ? 0 : dp(4), 0, i == actions.length - 1 ? 0 : dp(4), 0);
            audioRow.addView(actions[i], p);
        }
        addSpaced(audio, audioRow, 12);
        LinearLayout resultCard = card();
        resultCard.addView(text("ESTADO DE LA ORDEN", 11, COLOR_ACCENT, true));
        commandResultText = text("Conecta la PC para usar los controles.", 13, COLOR_MUTED, false);
        addSpaced(resultCard, commandResultText, 8);
        if (tabletLayout) {
            LinearLayout columns = new LinearLayout(this);
            columns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
            left.rightMargin = dp(10);
            columns.addView(power, left);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
            right.leftMargin = dp(10);
            columns.addView(audio, right);
            addSpaced(page, columns, 23);
            addSpaced(page, resultCard, 14);
        } else {
            addSpaced(page, power, 23);
            addSpaced(page, resultCard, 12);
            addSpaced(page, audio, 12);
        }
        return page;
    }

    private LinearLayout buildConnectionPage() {
        LinearLayout page = pageContent();
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(text("Conexión", 26, COLOR_TEXT, true));
        addSpaced(page, text("Usa el código que aparece en la PC.", 13, COLOR_MUTED, false), 5);
        LinearLayout connectionCard = card();
        connectionCard.addView(text("ENCONTRAR TU PC", 11, COLOR_ACCENT, true));
        discoveredText = text("Buscando tu computadora…", 14, COLOR_MUTED, false);
        addSpaced(connectionCard, discoveredText, 10);
        pinInput = field("Código de 6 dígitos");
        pinInput.setInputType(2);
        pinInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        pinInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                pairingPin = s.toString().trim();
                pairingReady = false;
                if (streamConnected) closeActiveSocket();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        LinearLayout pinRow = new LinearLayout(this);
        pinRow.setGravity(Gravity.CENTER_VERTICAL);
        pinRow.addView(pinInput, new LinearLayout.LayoutParams(0, dp(54), 1));
        quickConnectButton = button("Conectar", COLOR_ACCENT, COLOR_BG);
        LinearLayout.LayoutParams buttonParams = params(dp(112), dp(54));
        buttonParams.leftMargin = dp(8);
        pinRow.addView(quickConnectButton, buttonParams);
        quickConnectButton.setOnClickListener(view -> connectAutomatically());
        addSpaced(connectionCard, pinRow, 18);
        addSpaced(connectionCard, text("La dirección de la PC se detecta automáticamente en la red local.", 12, COLOR_MUTED, false), 10);

        discoveryButton = button("Introducir IP manual", COLOR_CARD_RAISED, COLOR_TEXT);
        discoveryButton.setOnClickListener(view -> toggleManualOptions());
        addSpaced(connectionCard, discoveryButton, 14);
        manualSection = new LinearLayout(this);
        manualSection.setOrientation(LinearLayout.VERTICAL);
        manualSection.setVisibility(View.GONE);
        networkModeHint = text("En la misma red: usa la IP local o la detección automática.", 12, COLOR_MUTED, false);
        addSpaced(manualSection, networkModeHint, 8);
        addressInput = field("IP local o Tailscale (100.x.x.x)");
        portInput = field("Puerto");
        portInput.setInputType(2);
        portInput.setText(String.valueOf(DEFAULT_PORT));
        LinearLayout manualRow = new LinearLayout(this);
        manualRow.addView(addressInput, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams portParams = params(dp(88), dp(50));
        portParams.leftMargin = dp(7);
        manualRow.addView(portInput, portParams);
        addSpaced(manualSection, manualRow, 10);
        Button manualConnect = button("Conectar a esta IP", COLOR_CARD_RAISED, COLOR_TEXT);
        addSpaced(manualSection, manualConnect, 8);
        manualConnect.setOnClickListener(view -> connectManually());
        connectionCard.addView(manualSection);
        Button vpnConnect = button("Redes diferentes · VPN privada", COLOR_CARD_RAISED, COLOR_TEXT);
        addSpaced(connectionCard, vpnConnect, 10);
        vpnConnect.setOnClickListener(view -> enableVpnMode());
        LinearLayout cable = card();
        cable.addView(text("CABLE USB", 11, COLOR_ACCENT, true));
        addSpaced(cable, text("Activa Compartir Internet por USB en Android. La conexión aparecerá como una red local; el cable de carga por sí solo no transmite la pantalla.", 12, COLOR_MUTED, false), 8);
        Button usbPrepare = button("Preparar conexión USB", COLOR_CARD_RAISED, COLOR_TEXT);
        addSpaced(cable, usbPrepare, 12);
        usbPrepare.setOnClickListener(view -> prepareUsbConnection());
        if (tabletLayout) {
            LinearLayout columns = new LinearLayout(this);
            columns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
            left.rightMargin = dp(10);
            columns.addView(connectionCard, left);
            LinearLayout rightColumn = new LinearLayout(this);
            rightColumn.setOrientation(LinearLayout.VERTICAL);
            rightColumn.addView(cable);
            LinearLayout steps = card();
            steps.addView(text("CONECTAR EN 3 PASOS", 11, COLOR_ACCENT, true));
            addSpaced(steps, text("1. Abre el emisor en Windows.\n2. Escribe el código de 6 dígitos.\n3. Pulsa Conectar.", 12, COLOR_MUTED, false), 10);
            addSpaced(rightColumn, steps, 14);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
            right.leftMargin = dp(10);
            columns.addView(rightColumn, right);
            addSpaced(page, columns, 23);
        } else {
            addSpaced(page, connectionCard, 23);
            addSpaced(page, cable, 14);
        }
        return page;
    }

    private void prepareUsbConnection() {
        vpnMode = false;
        manualSection.setVisibility(View.VISIBLE);
        addressInput.setText("127.0.0.1");
        addressInput.setHint("IP local o Tailscale (100.x.x.x)");
        portInput.setText(String.valueOf(DEFAULT_PORT));
        discoveryButton.setText("Ocultar IP manual");
        networkModeHint.setText("USB directo con ADB: inicia el reenvío desde Windows y conecta a 127.0.0.1.");
        setStatus("USB preparado. En Windows ejecuta adb reverse tcp:5050 tcp:5050 y pulsa Conectar a esta IP.", false);
    }

    private void buildViewer(FrameLayout frame) {
        viewerScreen = new FrameLayout(this);
        viewerScreen.setBackgroundColor(COLOR_BG);
        viewerScreen.setVisibility(View.GONE);
        imageView = new ImageView(this);
        imageView.setBackgroundColor(COLOR_BG);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewerScreen.addView(imageView, new FrameLayout.LayoutParams(-1, -1));
        imageView.setOnTouchListener((view, event) -> handleViewerTouch(event));
        frame.addView(viewerScreen, new FrameLayout.LayoutParams(-1, -1));
    }

    private View buildMark() {
        LinearLayout mark = new LinearLayout(this);
        mark.setOrientation(LinearLayout.VERTICAL);
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            for (int column = 0; column < 2; column++) {
                View square = new View(this);
                square.setBackground(rounded(row == column ? COLOR_ACCENT : Color.rgb(18, 144, 234), dp(2), COLOR_ACCENT, 0));
                LinearLayout.LayoutParams squareParams = params(dp(14), dp(14));
                squareParams.setMargins(0, 0, dp(3), dp(3));
                line.addView(square, squareParams);
            }
            mark.addView(line);
        }
        return mark;
    }

    private final class SymbolView extends View {
        private final String symbol;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tint;

        SymbolView(String symbol, int tint) {
            super(MainActivity.this);
            this.symbol = symbol;
            this.tint = tint;
        }

        void setTint(int value) { tint = value; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight());
            canvas.save();
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
            canvas.scale(size / 24f, size / 24f);
            paint.setColor(tint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            switch (symbol) {
                case "home": {
                    Path roof = new Path();
                    roof.moveTo(3, 11); roof.lineTo(12, 3); roof.lineTo(21, 11);
                    roof.moveTo(5, 10); roof.lineTo(5, 21); roof.lineTo(19, 21); roof.lineTo(19, 10);
                    roof.moveTo(10, 21); roof.lineTo(10, 14); roof.lineTo(14, 14); roof.lineTo(14, 21);
                    canvas.drawPath(roof, paint);
                    break;
                }
                case "monitor":
                    canvas.drawRoundRect(2, 3, 22, 17, 2, 2, paint);
                    canvas.drawLine(12, 17, 12, 21, paint);
                    canvas.drawLine(7, 21, 17, 21, paint);
                    break;
                case "touch":
                    canvas.drawRoundRect(3, 3, 21, 21, 3, 3, paint);
                    canvas.drawCircle(12, 10, 2.3f, paint);
                    canvas.drawLine(12, 13, 12, 19, paint);
                    break;
                case "power":
                    canvas.drawArc(3, 4, 21, 22, -50, 280, false, paint);
                    canvas.drawLine(12, 2, 12, 13, paint);
                    break;
                case "restart":
                    canvas.drawArc(3, 3, 21, 21, -65, 290, false, paint);
                    canvas.drawLine(19, 4, 21, 10, paint);
                    canvas.drawLine(21, 10, 15, 9, paint);
                    break;
                case "lock":
                    canvas.drawRoundRect(5, 11, 19, 22, 2, 2, paint);
                    canvas.drawArc(8, 2, 16, 14, 180, 180, false, paint);
                    break;
                case "connect":
                    canvas.drawCircle(6, 7, 3, paint);
                    canvas.drawCircle(18, 17, 3, paint);
                    canvas.drawLine(8.5f, 9, 15.5f, 15, paint);
                    break;
                case "volume":
                case "mute": {
                    Path speaker = new Path();
                    speaker.moveTo(3, 9); speaker.lineTo(8, 9); speaker.lineTo(13, 5);
                    speaker.lineTo(13, 19); speaker.lineTo(8, 15); speaker.lineTo(3, 15); speaker.close();
                    canvas.drawPath(speaker, paint);
                    if (symbol.equals("mute")) {
                        canvas.drawLine(17, 9, 22, 15, paint);
                        canvas.drawLine(22, 9, 17, 15, paint);
                    } else {
                        canvas.drawArc(11, 7, 20, 17, -65, 130, false, paint);
                        canvas.drawArc(10, 3, 24, 21, -65, 130, false, paint);
                    }
                    break;
                }
                default:
                    canvas.drawCircle(12, 12, 8, paint);
            }
            canvas.restore();
        }
    }

    private void buildBottomNav() {
        bottomNav = new LinearLayout(this);
        bottomNav.setGravity(Gravity.CENTER_VERTICAL);
        bottomNav.setPadding(dp(8), dp(6), dp(8), dp(6));
        bottomNav.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(14, 37, 72), Color.rgb(8, 24, 48)}));
        addNavItem("Inicio", "home");
        addNavItem("Remoto", "monitor");
        addNavItem("Controles", "power");
        addNavItem("Conexión", "connect");
        for (int i = 0; i < 4; i++) {
            final int selectedPage = i;
            bottomNav.getChildAt(i).setOnClickListener(view -> showPage(selectedPage));
        }
    }

    private void addNavItem(String label, String symbol) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.addView(new SymbolView(symbol, COLOR_MUTED), params(dp(22), dp(22)));
        TextView caption = text(label, 11, COLOR_MUTED, false);
        LinearLayout.LayoutParams captionParams = params(-1, -2);
        captionParams.topMargin = dp(3);
        caption.setGravity(Gravity.CENTER);
        item.addView(caption, captionParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(56), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        bottomNav.addView(item, params);
    }

    private void showPage(int selected) {
        if (pages == null || selected < 0 || selected >= pages.length) return;
        if (pageIndex != selected) {
            for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == selected ? View.VISIBLE : View.GONE);
            pages[selected].setAlpha(0f);
            pages[selected].animate().alpha(1f).setDuration(160).start();
            pageIndex = selected;
        }
        for (int i = 0; i < bottomNav.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) bottomNav.getChildAt(i);
            boolean active = i == selected;
            item.setBackground(rounded(active ? Color.rgb(28, 75, 131) : Color.TRANSPARENT,
                    dp(13), COLOR_BORDER, 0));
            ((SymbolView) item.getChildAt(0)).setTint(active ? COLOR_ACCENT : COLOR_MUTED);
            ((TextView) item.getChildAt(1)).setTextColor(active ? COLOR_TEXT : COLOR_MUTED);
        }
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(31, 61, 108), COLOR_CARD, Color.rgb(16, 32, 60)});
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), COLOR_BORDER);
        layout.setBackground(background);
        return layout;
    }

    private EditText field(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(COLOR_TEXT);
        field.setHintTextColor(Color.rgb(120, 124, 136));
        field.setHint(hint);
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(rounded(COLOR_BG, dp(10), COLOR_BORDER, dp(1)));
        return field;
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setBackground(rounded(background, dp(10), background, 0));
        addPressFeedback(button);
        return button;
    }

    private View actionButton(String icon, String label, String confirmation, int command) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(16), dp(12), dp(12), dp(12));
        int color = command == 2 ? Color.rgb(26, 70, 132) : COLOR_CARD;
        tile.setBackground(rounded(color, dp(14), COLOR_BORDER, dp(1)));
        tile.addView(new SymbolView(icon, COLOR_ACCENT), params(dp(26), dp(26)));
        TextView labelText = text(label, 14, COLOR_TEXT, true);
        LinearLayout.LayoutParams labelParams = params(-1, -2);
        labelParams.topMargin = dp(8);
        tile.addView(labelText, labelParams);
        tile.setOnClickListener(view -> {
            if (command == 0) { showViewer(false); return; }
            if (confirmation != null) {
                new AlertDialog.Builder(this)
                        .setTitle(label)
                        .setMessage(confirmation + ". ¿Continuar?")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Continuar", (dialog, which) -> sendCommand(command))
                        .show();
            } else sendCommand(command);
        });
        addPressFeedback(tile);
        return tile;
    }

    private void addPressFeedback(View view) {
        view.setOnTouchListener((target, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                target.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                target.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
            return false;
        });
    }

    private GridLayout.LayoutParams gridParams(int column) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(column, 1f));
        params.width = 0;
        params.height = dp(78);
        params.setMargins(column == 0 ? 0 : dp(7), dp(4), column == 0 ? dp(7) : 0, dp(4));
        return params;
    }

    private void connectManually() {
        String host = addressInput.getText().toString().trim();
        int port;
        try { port = Integer.parseInt(portInput.getText().toString().trim()); }
        catch (NumberFormatException error) { setStatus("El puerto no es válido.", true); return; }
        String pin = pinInput.getText().toString().trim();
        if (host.isEmpty()) { setStatus("Escribe la IP de la PC.", true); return; }
        if (port < 1 || port > 65535) { setStatus("El puerto debe estar entre 1 y 65535.", true); return; }
        if (pin.length() != 6) { setStatus("Escribe el código de 6 dígitos que aparece en la PC.", true); return; }
        if (vpnMode && !isTailscaleIpv4(host)) {
            setStatus("En modo VPN escribe la IP Tailscale 100.64–100.127.x.x de Windows.", true);
            return;
        }
        discoveryEnabled = false;
        manualHost = host;
        manualPort = port;
        pairingPin = pin;
        pairingReady = true;
        closeActiveSocket();
        setStatus("Conectando con " + host + "…", false);
    }

    private void connectAutomatically() {
        String pin = pinInput.getText().toString().trim();
        if (pin.length() != 6) { setStatus("Escribe los 6 dígitos que aparecen en la PC.", true); return; }
        vpnMode = false;
        pairingPin = pin;
        pairingReady = true;
        discoveryEnabled = true;
        manualHost = null;
        closeActiveSocket();
        setStatus(discoveredHost == null ? "Buscando tu PC en la red local…" : "Conectando con tu computadora…", false);
    }

    private void toggleManualOptions() {
        vpnMode = false;
        boolean show = manualSection.getVisibility() != View.VISIBLE;
        manualSection.setVisibility(show ? View.VISIBLE : View.GONE);
        discoveryButton.setText(show ? "Ocultar IP manual" : "Introducir IP manual");
        addressInput.setHint("IP local o Tailscale (100.x.x.x)");
        networkModeHint.setText("En la misma red: usa la IP local o la detección automática.");
    }

    private void enableVpnMode() {
        vpnMode = true;
        discoveryEnabled = false;
        pairingReady = false;
        manualHost = null;
        closeActiveSocket();
        manualSection.setVisibility(View.VISIBLE);
        discoveryButton.setText("Modo redes distintas · VPN privada");
        addressInput.setText("");
        addressInput.setHint("IP Tailscale de Windows · 100.x.x.x");
        networkModeHint.setText("Instala Tailscale en Windows y Android, inicia sesión en la misma red privada y escribe la IP 100.x de Windows. El túnel cifra el tráfico; no abras puertos del router.");
        setStatus("Modo VPN listo. Ambos dispositivos deben tener Tailscale conectado.", false);
    }

    private boolean isTailscaleIpv4(String host) {
        String[] octets = host.split("\\.");
        if (octets.length != 4) return false;
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            int third = Integer.parseInt(octets[2]);
            int fourth = Integer.parseInt(octets[3]);
            return first == 100 && second >= 64 && second <= 127
                    && third >= 0 && third <= 255 && fourth >= 0 && fourth <= 255;
        } catch (NumberFormatException ignored) { return false; }
    }

    private void startDiscovery() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("proyector-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) { }

        Thread discovery = new Thread(() -> {
            byte[] buffer = new byte[256];
            try (DatagramSocket datagram = new DatagramSocket(DISCOVERY_PORT)) {
                datagram.setSoTimeout(1500);
                while (alive) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        datagram.receive(packet);
                        String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                        if (!message.startsWith("PROYECTOR/1:")) continue;
                        int port = Integer.parseInt(message.substring("PROYECTOR/1:".length()).trim());
                        String host = packet.getAddress().getHostAddress();
                        boolean changed = !host.equals(discoveredHost) || port != discoveredPort;
                        discoveredPort = port;
                        if (changed) {
                            discoveredHost = host;
                            onDiscovered(host, port);
                        }
                    } catch (SocketTimeoutException ignored) { }
                    catch (Exception ignored) { }
                }
            } catch (IOException error) {
                setStatus("No se pudo iniciar la búsqueda automática. Puedes conectar por IP.", true);
            }
        }, "proyector-auto-discovery");
        discovery.setDaemon(true);
        discovery.start();
    }

    private void onDiscovered(String host, int port) {
        mainHandler.post(() -> {
            if (!alive) return;
            discoveredText.setText("PC encontrada · " + host + ":" + port);
            discoveredText.setTextColor(COLOR_ACCENT);
            deviceStatus.setText("●  PC detectada · " + host);
            deviceStatus.setTextColor(COLOR_ACCENT);
            if (addressInput.getText().length() == 0 || discoveryEnabled) addressInput.setText(host);
            if (discoveryEnabled && pairingReady) {
                setStatus("PC encontrada. Conectando…", false);
                closeActiveSocket();
            } else if (discoveryEnabled) {
                setStatus("PC encontrada. Escribe el código para conectar.", false);
            }
        });
    }

    private void startConnectionLoop() {
        Thread connectionLoop = new Thread(() -> {
            while (alive) {
                String host = discoveryEnabled ? discoveredHost : manualHost;
                int port = discoveryEnabled ? discoveredPort : manualPort;
                String pin = pairingPin;
                if (!pairingReady || host == null || host.isEmpty() || pin.length() != 6) {
                    sleep(400);
                    continue;
                }

                Socket connected = new Socket();
                activeSocket = connected;
                activeHost = host;
                try {
                    connected.connect(new InetSocketAddress(host, port), 5000);
                    connected.setTcpNoDelay(true);
                    connected.setKeepAlive(true);
                    DataOutputStream output = new DataOutputStream(connected.getOutputStream());
                    output.writeUTF(pin);
                    output.flush();
                    connected.setSoTimeout(9000);
                    DataInputStream input = new DataInputStream(new BufferedInputStream(connected.getInputStream()));
                    int authorization = input.readInt();
                    if (authorization != 0) {
                        pairingReady = false;
                        setStatus("Código incorrecto. Comprueba el código mostrado en Windows.", true);
                        continue;
                    }
                    connected.setSoTimeout(0);
                    activeOutput = output;
                    streamConnected = true;
                    mainHandler.post(() -> {
                        quickConnectButton.setText("Conectado");
                        deviceStatus.setText("●  Conectado · " + host);
                        deviceStatus.setTextColor(Color.rgb(60, 221, 133));
                        if (volumeSeekBar != null) volumeSeekBar.setEnabled(true);
                    });
                    setStatus("Conectado · recibiendo pantalla", false);
                    sendPacket(15);
                    long nextStatusRefresh = System.currentTimeMillis() + 30000;
                    while (alive && connected == activeSocket) {
                        int length;
                        try { length = input.readInt(); }
                        catch (EOFException end) { break; }
                        if (length == -2) {
                            boolean success = input.readBoolean();
                            String message = input.readUTF();
                            showCommandResult(message, success);
                            continue;
                        }
                        if (length <= 0 || length > MAX_FRAME_BYTES) throw new IOException("Fotograma inválido");
                        byte[] bytes = new byte[length];
                        input.readFully(bytes);
                        if (framePending.compareAndSet(false, true)) {
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inPreferredConfig = Bitmap.Config.RGB_565;
                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                            if (bitmap != null) mainHandler.post(() -> {
                                frameWidth = bitmap.getWidth();
                                frameHeight = bitmap.getHeight();
                                imageView.setImageBitmap(bitmap);
                                framePending.set(false);
                            });
                            else framePending.set(false);
                        }
                        if (System.currentTimeMillis() >= nextStatusRefresh) {
                            sendPacket(15);
                            nextStatusRefresh = System.currentTimeMillis() + 30000;
                        }
                    }
                } catch (Exception error) {
                    if (alive && connected == activeSocket) setStatus("Conexión perdida. Buscando otra vez…", true);
                } finally {
                    streamConnected = false;
                    activeOutput = null;
                    mainHandler.post(() -> {
                        quickConnectButton.setText("Conectar");
                        deviceStatus.setText("●  Sin conexión");
                        deviceStatus.setTextColor(COLOR_MUTED);
                        if (volumeSeekBar != null) volumeSeekBar.setEnabled(false);
                        if (volumeLevelText != null) volumeLevelText.setText("Volumen de Windows · sin conexión");
                        if (pcBatteryText != null) pcBatteryText.setText("Batería de Windows · sin conexión");
                    });
                    try { connected.close(); } catch (IOException ignored) { }
                    if (connected == activeSocket) activeSocket = null;
                }
                sleep(1300);
            }
        }, "proyector-stream-loop");
        connectionLoop.setDaemon(true);
        connectionLoop.start();
    }

    private void sendCommand(int command) {
        commandExecutor.execute(() -> {
            DataOutputStream output = activeOutput;
            if (!streamConnected || output == null) {
                showCommandResult("Conecta la PC antes de usar sus controles.", false);
                return;
            }
            try {
                output.writeInt(command);
                output.flush();
                showCommandResult("Orden enviada. Esperando respuesta de Windows…", true);
            } catch (IOException error) {
                showCommandResult("No se pudo enviar la orden. Revisa la conexión.", false);
            }
        });
    }

    private void showCommandResult(String message, boolean success) {
        mainHandler.post(() -> {
            String displayMessage = message;
            if (message != null && message.startsWith("STAT:")) {
                try {
                    String[] parts = message.split(":");
                    if (parts.length < 5) throw new IllegalArgumentException("Estado incompleto");
                    int percent = Math.max(0, Math.min(100, Integer.parseInt(parts[1])));
                    boolean muted = "1".equals(parts[2]);
                    updatingVolumeFromPc = true;
                    if (volumeSeekBar != null) volumeSeekBar.setProgress(percent);
                    if (volumeLevelText != null) volumeLevelText.setText("Volumen de Windows: " + percent + "%" + (muted ? " · silenciado" : ""));
                    updatingVolumeFromPc = false;
                    String batteryStatus;
                    if ("NA".equals(parts[3])) {
                        batteryStatus = "sin batería detectada";
                        if (pcBatteryText != null) pcBatteryText.setText("Batería de Windows · no detectada");
                    } else {
                        int battery = Math.max(0, Math.min(100, Integer.parseInt(parts[3])));
                        boolean charging = "1".equals(parts[4]);
                        batteryStatus = "batería " + battery + "%" + (charging ? " · cargando" : "");
                        if (pcBatteryText != null) pcBatteryText.setText("Batería de Windows: " + battery + "%" + (charging ? " · cargando" : ""));
                    }
                    displayMessage = "Volumen " + percent + "%" + (muted ? " · silenciado" : "") + " · " + batteryStatus;
                } catch (RuntimeException ignored) { updatingVolumeFromPc = false; }
            } else if (message != null && message.startsWith("VOL:")) {
                try {
                    String[] parts = message.split(":");
                    int percent = Math.max(0, Math.min(100, Integer.parseInt(parts[1])));
                    boolean muted = parts.length > 2 && "1".equals(parts[2]);
                    updatingVolumeFromPc = true;
                    if (volumeSeekBar != null) volumeSeekBar.setProgress(percent);
                    if (volumeLevelText != null) volumeLevelText.setText("Volumen de Windows: " + percent + "%" + (muted ? " · silenciado" : ""));
                    updatingVolumeFromPc = false;
                    displayMessage = "Volumen de Windows: " + percent + "%" + (muted ? " · silenciado" : "");
                } catch (RuntimeException ignored) { updatingVolumeFromPc = false; }
            }
            if (commandResultText != null) {
                commandResultText.setText(displayMessage);
                commandResultText.setTextColor(success ? COLOR_TEXT : Color.rgb(255, 190, 119));
            }
            setStatus(displayMessage, !success);
        });
    }

    private void showViewer(boolean panelMode) {
        if (!streamConnected) {
            setStatus("Primero conecta la PC para ver la pantalla.", true);
            return;
        }
        touchpadMode = panelMode;
        appUi.setVisibility(View.GONE);
        viewerScreen.animate().cancel();
        viewerScreen.setAlpha(0f);
        viewerScreen.setVisibility(View.VISIBLE);
        viewerScreen.animate().alpha(1f).setDuration(180).start();
        applyViewerSystemUi();
    }

    private void showHome() {
        viewerScreen.animate().cancel();
        viewerScreen.setVisibility(View.GONE);
        appUi.setAlpha(0f);
        appUi.setVisibility(View.VISIBLE);
        appUi.animate().alpha(1f).setDuration(180).start();
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private void applyViewerSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && viewerScreen != null && viewerScreen.getVisibility() == View.VISIBLE) applyViewerSystemUi();
    }

    private boolean handleViewerTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchStartX = lastX = event.getX();
            touchStartY = lastY = event.getY();
            gestureMoved = longPressed = dragging = twoFingerScroll = false;
            lastPointerDispatch = 0;
            pendingRelativeX = pendingRelativeY = 0;
            secondTapHold = touchpadMode && System.currentTimeMillis() - lastTapAt < 330;
            longPressTask = () -> { if (!gestureMoved && !twoFingerScroll) longPressed = true; };
            mainHandler.postDelayed(longPressTask, 550);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            cancelLongPress();
            gestureMoved = true;
            if (event.getPointerCount() == 3) {
                showKeyboardDialog();
                return true;
            }
            twoFingerScroll = true;
            scrollY = (event.getY(0) + event.getY(1)) / 2f;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && twoFingerScroll && event.getPointerCount() >= 2) {
            float currentY = (event.getY(0) + event.getY(1)) / 2f;
            int ticks = (int) ((scrollY - currentY) / dp(28));
            if (ticks != 0) { sendPacket(9, Math.max(-12, Math.min(12, ticks))); scrollY = currentY; }
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) { twoFingerScroll = false; gestureMoved = true; return true; }
        if (action == MotionEvent.ACTION_MOVE && !twoFingerScroll) {
            float x = event.getX(), y = event.getY();
            if (Math.hypot(x - touchStartX, y - touchStartY) > dp(8)) {
                if (!gestureMoved && !longPressed) cancelLongPress();
                gestureMoved = true;
            }
            if (touchpadMode) {
                if (gestureMoved) {
                    if ((secondTapHold || longPressed) && !dragging) { sendPacket(6, -1, -1); dragging = true; }
                    pendingRelativeX += (x - lastX) * 1.4f;
                    pendingRelativeY += (y - lastY) * 1.4f;
                    if (System.currentTimeMillis() - lastPointerDispatch >= 16) flushRelativeMotion();
                }
            } else if (gestureMoved) {
                int[] position = imagePosition(x, y);
                if (position != null) {
                    if (!dragging) {
                        int[] start = imagePosition(touchStartX, touchStartY);
                        if (start != null) { sendPacket(6, start[0], start[1]); dragging = true; }
                    }
                    if (System.currentTimeMillis() - lastPointerDispatch >= 16) {
                        sendPacket(5, position[0], position[1]);
                        lastPointerDispatch = System.currentTimeMillis();
                    }
                    lastDirectX = position[0];
                    lastDirectY = position[1];
                }
            }
            lastX = x; lastY = y;
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelLongPress();
            if (touchpadMode) flushRelativeMotion();
            if (dragging) {
                if (touchpadMode) sendPacket(7, -1, -1);
                else {
                    int[] position = imagePosition(event.getX(), event.getY());
                    if (position != null) sendPacket(7, position[0], position[1]);
                    else sendPacket(7, lastDirectX, lastDirectY);
                }
            } else if (action == MotionEvent.ACTION_UP && !gestureMoved) {
                if (longPressed) {
                    if (!touchpadMode) {
                        int[] position = imagePosition(event.getX(), event.getY());
                        if (position != null) sendPacket(5, position[0], position[1]);
                    }
                    sendPacket(8);
                }
                else if (touchpadMode) { sendPacket(6, -1, -1); sendPacket(7, -1, -1); lastTapAt = System.currentTimeMillis(); }
                else {
                    int[] position = imagePosition(event.getX(), event.getY());
                    if (position != null) { sendPacket(6, position[0], position[1]); sendPacket(7, position[0], position[1]); }
                }
            }
            dragging = false;
            return true;
        }
        return true;
    }

    private void cancelLongPress() {
        if (longPressTask != null) mainHandler.removeCallbacks(longPressTask);
        longPressTask = null;
    }

    private void flushRelativeMotion() {
        int dx = Math.round(pendingRelativeX), dy = Math.round(pendingRelativeY);
        pendingRelativeX -= dx;
        pendingRelativeY -= dy;
        if (dx != 0 || dy != 0) {
            sendPacket(13, Math.max(-1000, Math.min(1000, dx)), Math.max(-1000, Math.min(1000, dy)));
            lastPointerDispatch = System.currentTimeMillis();
        }
    }

    private int[] imagePosition(float touchX, float touchY) {
        int width = frameWidth, height = frameHeight;
        if (width <= 0 || height <= 0 || imageView.getWidth() == 0 || imageView.getHeight() == 0) return null;
        float scale = Math.min(imageView.getWidth() / (float) width, imageView.getHeight() / (float) height);
        float left = (imageView.getWidth() - width * scale) / 2f;
        float top = (imageView.getHeight() - height * scale) / 2f;
        float x = (touchX - left) / (width * scale), y = (touchY - top) / (height * scale);
        if (x < 0 || x > 1 || y < 0 || y > 1) return null;
        return new int[]{Math.round(x * 1000000), Math.round(y * 1000000)};
    }

    private void showKeyboardDialog() {
        EditText input = field("Escribe para Windows");
        input.setSingleLine(false);
        input.setMaxLines(4);
        new AlertDialog.Builder(this).setTitle("Escribir en la PC").setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Enviar", (dialog, which) -> sendText(input.getText().toString()))
                .show();
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void sendPacket(int command, int... arguments) {
        commandExecutor.execute(() -> {
            DataOutputStream output = activeOutput;
            if (!streamConnected || output == null) return;
            try {
                output.writeInt(command);
                for (int argument : arguments) output.writeInt(argument);
                output.flush();
            } catch (IOException error) { setStatus("Se perdió la conexión con la PC.", true); }
        });
    }

    private void sendText(String value) {
        if (value.isEmpty()) return;
        if (value.length() > 512) { setStatus("El texto supera 512 caracteres.", true); return; }
        commandExecutor.execute(() -> {
            DataOutputStream output = activeOutput;
            if (!streamConnected || output == null) { setStatus("Conecta la PC antes de escribir.", true); return; }
            try { output.writeInt(14); output.writeUTF(value); output.flush(); }
            catch (IOException error) { setStatus("No se pudo enviar el texto.", true); }
        });
    }

    private void closeActiveSocket() {
        Socket socket = activeSocket;
        if (socket != null) commandExecutor.execute(() -> {
            try { socket.close(); } catch (IOException ignored) { }
        });
    }

    private void setStatus(String message, boolean error) {
        mainHandler.post(() -> {
            if (statusText != null) {
                int sequence = ++statusSequence;
                statusText.setText(message);
                statusText.setTextColor(error ? Color.rgb(255, 161, 167) : COLOR_TEXT);
                statusText.setBackgroundColor(error ? Color.rgb(98, 42, 57) : Color.rgb(20, 57, 97));
                statusText.setVisibility(View.VISIBLE);
                mainHandler.postDelayed(() -> {
                    if (sequence == statusSequence && statusText != null) statusText.setVisibility(View.GONE);
                }, 4500);
            }
        });
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static void sleep(long millis) { try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }

    @Override
    public void onBackPressed() {
        if (viewerScreen != null && viewerScreen.getVisibility() == View.VISIBLE) showHome();
        else if (pageIndex != 0) showPage(0);
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        closeActiveSocket();
        commandExecutor.shutdown();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }
}
