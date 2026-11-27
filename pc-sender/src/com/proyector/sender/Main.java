package com.proyector.sender;

import com.proyector.shared.Wire;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.BindException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

public final class Main {
    private static final Color INK = new Color(0x101E38);
    private static final Color[] LIGHT = palette(0xF1F5F9, 0xFFFFFF, 0xEBF1F7, 0xCEDBE6, 0x12253A, 0x4E6277, 0x1155C2, 0x963B1E);
    private static final Color[] DARK = palette(0x0C1524, 0x172639, 0x20334A, 0x364A60, 0xF0F6FF, 0xB4C4D6, 0x9DF5D1, 0xFFBA96);
    private static Color BACKGROUND, CARD, WELL, BORDER, TEXT, MUTED, ACCENT, AMBER;
    private static Color[] currentPalette = LIGHT;
    static { usePalette(LIGHT); }

    private static Color[] palette(int... rgb) {
        return java.util.Arrays.stream(rgb).mapToObj(Color::new).toArray(Color[]::new);
    }

    private static void usePalette(Color[] colors) {
        currentPalette = colors;
        BACKGROUND = colors[0]; CARD = colors[1]; WELL = colors[2]; BORDER = colors[3];
        TEXT = colors[4]; MUTED = colors[5]; ACCENT = colors[6]; AMBER = colors[7];
    }

    private static Color mapped(Color value, Color[] from) {
        for (int i = 0; i < from.length; i++) if (value == from[i]) return currentPalette[i];
        return value;
    }

    private static void recolor(Component part, Color[] from) {
        if (part instanceof Surface surface) {
            surface.fill = mapped(surface.fill, from);
            surface.border = mapped(surface.border, from);
        }
        part.setBackground(mapped(part.getBackground(), from));
        part.setForeground(mapped(part.getForeground(), from));
        if (part instanceof JComboBox<?> combo) combo.updateUI();
        if (part instanceof Container group) for (Component child : group.getComponents()) recolor(child, from);
    }

    private static final Color MINT = new Color(0x9DF5D1);
    private static final Color ON_INK = new Color(0xF3FAFF);
    private static final Color SUBTLE_INK = new Color(0xD4E6FB);
    private static final String[] SECTIONS = {"Vinculación", "Pantallas", "Red"};

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && "--write-icon".equals(args[0])) {
            writeIcon(Path.of(args[1]));
            return;
        }
        SwingUtilities.invokeLater(() -> {
            int theme = Preferences.userRoot().node("/orbivia/sender").getInt("appearance", 0);
            usePalette(theme == 2 || (theme == 0 && systemDark()) ? DARK : LIGHT);
            comboColors();
            try { new SenderWindow().setVisible(true); }
            catch (RuntimeException error) {
                error.printStackTrace();
                JOptionPane.showMessageDialog(null, "Windows no pudo abrir el emisor. Comprueba que extrajiste la carpeta completa.",
                        "Error de inicio de Orbivía", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static void comboColors() {
        UIManager.put("ComboBox.background", WELL);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", WELL);
        UIManager.put("ComboBox.selectionForeground", TEXT);
        UIManager.put("ComboBox.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("OptionPane.background", CARD);
        UIManager.put("Panel.background", CARD);
        UIManager.put("Button.disabledText", MUTED);
        UIManager.put("OptionPane.messageForeground", TEXT);
    }

    private static boolean systemDark() {
        try {
            Process query = new ProcessBuilder(ConnectionSetup.windowsTool("reg.exe").toString(), "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!query.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) { query.destroyForcibly(); return false; }
            return new String(query.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim().endsWith("0x0");
        } catch (Exception unavailable) { return false; }
    }

    private static final class SenderWindow extends JFrame {
        private final String computer = Wire.displayName(System.getenv().getOrDefault("COMPUTERNAME", "Windows"));
        private final Preferences preferences = Preferences.userRoot().node("/orbivia/sender");
        private final WindowsControl controls = new WindowsControl();
        private final JLabel connection = label("Emisor detenido", 13, MUTED, true);
        private final JLabel video = label("Imagen en pausa", 12, MUTED, false);
        private final JLabel videoLimit = label("", 12, ACCENT, true);
        private final JLabel pin = label("··· ···", 38, ACCENT, true);
        private final JLabel identityLabel = label("Preparando identidad…", 12, MUTED, false);
        private final JLabel displayHint = label("Buscando pantallas en Windows…", 12, MUTED, false);
        private final JLabel message = label("Inicia el emisor para mostrar el código de conexión.", 13, MUTED, false);
        private final JButton toggle = button("Iniciar emisor", true);
        private final JButton copyPin = button("Copiar código", false);
        private final JTextField port = new JTextField(Integer.toString(
                Math.clamp(preferences.getInt("port", Wire.PORT), 1024, 65535)), 5);
        private final JComboBox<Option> desktop = new FieldCombo<>();
        private final JComboBox<Option> secondary = new FieldCombo<>();
        private final JComboBox<String> resolution = new FieldCombo<>(Wire.RESOLUTIONS.clone());
        private final JComboBox<String> fps = new FieldCombo<>(new String[]{"12 FPS", "20 FPS", "30 FPS", "45 FPS", "60 FPS"});
        private final JButton usbSetup = button("Preparar cable ADB", false);
        private final JButton virtualSetup = button("Preparar monitor virtual", true);
        private final JPanel routes = column();
        private final JLabel lanState = label("Buscando adaptador", 12, MUTED, false);
        private final JLabel usbState = label("Conecta el cable", 12, MUTED, false);
        private final JLabel vpnState = label("Abre Tailscale", 12, MUTED, false);
        private final JPanel usbSection = card();
        private final JPanel vpnSection = card();
        private final CardLayout layout = new CardLayout();
        private final MotionPane section = new MotionPane(layout);
        private final TabStrip tabs = new TabStrip();
        private final MonitorMap monitorMap = new MonitorMap();
        private final JButton[] navigation = new JButton[SECTIONS.length];
        private final AtomicReference<Displays.Settings> settings = new AtomicReference<>(new Displays.Settings(
                preferences.get("desktop", ""), preferences.get("secondary", ""),
                Math.clamp(preferences.getInt("quality", 3), 0, Wire.RESOLUTIONS.length - 1),
                Wire.FRAME_RATES[Math.clamp(preferences.getInt("rateIndex", 4), 0, Wire.FRAME_RATES.length - 1)]));
        private final JComboBox<String> appearance = new FieldCombo<>(new String[]{"Sistema", "Blanco", "Oscuro"});
        private int themeGeneration;
        private HostIdentity identity;
        private HostServer server;
        private int serverGeneration;
        private boolean loadingDisplays;
        private String displaySignature = "";
        private List<HostNetwork.Route> currentRoutes = List.of();
        private final Timer refresh;

        SenderWindow() {
            super("Orbivía · Emisor");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setMinimumSize(new Dimension(640, 490));
            setSize(960, 685);
            setLocationByPlatform(true);
            setIconImage(mark(48));

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(BACKGROUND);
            setContentPane(root);
            root.add(header(), BorderLayout.NORTH);
            JPanel work = new JPanel(new BorderLayout());
            work.setBorder(BorderFactory.createEmptyBorder(13, 19, 12, 19));
            work.setOpaque(false);
            section.add(page(pairingCard()), SECTIONS[0]);
            section.add(page(displayCard()), SECTIONS[1]);
            section.add(page(networkCard()), SECTIONS[2]);
            work.add(section, BorderLayout.CENTER);
            root.add(work, BorderLayout.CENTER);
            JPanel footer = new JPanel(new BorderLayout(12, 0));
            footer.setBackground(CARD);
            footer.setBorder(BorderFactory.createEmptyBorder(9, 20, 10, 20));
            footer.add(message, BorderLayout.CENTER);
            JPanel stats = inline();
            stats.add(video);
            stats.add(videoLimit);
            footer.add(stats, BorderLayout.EAST);
            root.add(footer, BorderLayout.SOUTH);
            selectSection(0, false);

            toggle.setEnabled(false);
            toggle.addActionListener(event -> { if (server == null) startServer(); else stopServer(); });
            copyPin.setEnabled(false);
            copyPin.addActionListener(event -> { if (server != null) copy(server.pin(), "Código copiado"); });
            desktop.addActionListener(event -> updateSettings());
            secondary.addActionListener(event -> updateSettings());
            resolution.setSelectedIndex(settings.get().quality());
            fps.setSelectedItem(settings.get().fps() + " FPS");
            updateVideoLimit();
            resolution.addActionListener(event -> updateSettings());
            fps.addActionListener(event -> updateSettings());
            refresh = new Timer(1600, event -> refreshHardware());
            refreshHardware();
            refresh.start();
            addWindowListener(new WindowAdapter() {
                @Override public void windowActivated(WindowEvent event) { if (appearance.getSelectedIndex() == 0) updateAppearance(); }
                @Override public void windowClosed(WindowEvent event) {
                    refresh.stop();
                    section.motion.stop();
                    tabs.motion.stop();
                    stopServer();
                }
            });
            loadIdentity();
        }

        private void updateAppearance() {
            int choice = appearance.getSelectedIndex();
            int generation = ++themeGeneration;
            preferences.putInt("appearance", choice);
            if (choice == 0) {
                WindowsControl.daemon(() -> {
                    boolean dark = systemDark();
                    SwingUtilities.invokeLater(() -> { if (generation == themeGeneration && isDisplayable()) applyAppearance(dark); });
                }, "windows-theme").start();
            } else applyAppearance(choice == 2);
        }

        private void applyAppearance(boolean dark) {
            Color[] next = dark ? DARK : LIGHT;
            Color[] previous = currentPalette;
            if (next == previous) return;
            usePalette(next);
            comboColors();
            recolor(getContentPane(), previous);
            port.setCaretColor(ACCENT);
            repaint();
        }

        private JPanel header() {
            JPanel chrome = new JPanel(new BorderLayout());
            JPanel head = new JPanel(new BorderLayout(16, 0));
            head.setBackground(INK);
            head.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 16));
            JPanel brand = new JPanel(new BorderLayout(10, 0));
            brand.setOpaque(false);
            brand.add(new JLabel(new ImageIcon(mark(43))), BorderLayout.WEST);
            JPanel names = column();
            addSpaced(names, editorial("Orbivía", 22, ON_INK), 0);
            JLabel name = label(computer, 12, SUBTLE_INK, false);
            name.setToolTipText(computer);
            addSpaced(names, name, 0);
            brand.add(names);
            head.add(brand, BorderLayout.CENTER);
            appearance.setSelectedIndex(Math.clamp(preferences.getInt("appearance", 0), 0, 2));
            appearance.getAccessibleContext().setAccessibleName("Apariencia");
            appearance.addActionListener(event -> updateAppearance());
            JPanel utilities = inline();
            JLabel themeLabel = label("Apariencia", 12, SUBTLE_INK, false);
            themeLabel.setLabelFor(appearance);
            utilities.add(themeLabel);
            utilities.add(appearance);
            JButton exit = button("Salir", false);
            exit.addActionListener(event -> dispose());
            utilities.add(exit);
            head.add(utilities, BorderLayout.EAST);
            chrome.add(head, BorderLayout.NORTH);

            JPanel bar = new JPanel(new BorderLayout(10, 0));
            bar.setBackground(CARD);
            bar.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 20));
            for (int i = 0; i < SECTIONS.length; i++) {
                int index = i;
                JButton tab = new StyledButton(SECTIONS[i], false, true);
                tab.setIcon(new SectionIcon(i));
                tab.setIconTextGap(7);
                tab.addActionListener(event -> selectSection(index, true));
                navigation[i] = tab;
                tabs.add(tab);
            }
            bar.add(tabs, BorderLayout.WEST);
            bar.add(connection, BorderLayout.EAST);
            chrome.add(bar, BorderLayout.SOUTH);
            return chrome;
        }

        private static JScrollPane page(JPanel contents) {
            JPanel stack = new ScrollColumn();
            stack.setOpaque(false);
            stack.add(contents, BorderLayout.NORTH);
            JScrollPane scroll = new JScrollPane(stack);
            scroll.setBorder(null);
            scroll.getViewport().setBackground(BACKGROUND);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(18);
            return scroll;
        }

        private void selectSection(int selected, boolean animate) {
            if (animate && navigation[selected].isSelected()) return;
            for (int i = 0; i < navigation.length; i++) {
                navigation[i].setSelected(i == selected);
                navigation[i].setForeground(i == selected ? ACCENT : MUTED);
            }
            tabs.select(selected, animate);
            layout.show(section, SECTIONS[selected]);
            if (animate) section.enter();
        }

        private JPanel pairingCard() {
            JPanel page = column();
            JPanel hero = new StudioBanner();
            hero.setLayout(new BorderLayout(12, 0));
            hero.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 14));
            JPanel details = column();
            addSpaced(details, editorial("Vincular Android", 28, ON_INK), 0);
            addSpaced(details, label("Código de conexión", 13, SUBTLE_INK, false), 9);
            pin.setFont(new Font("Consolas", Font.BOLD, 44));
            pin.setForeground(MINT);
            pin.getAccessibleContext().setAccessibleName("Código de conexión");
            addSpaced(details, pin, 1);
            addSpaced(details, copyPin, 3);
            hero.add(details, BorderLayout.CENTER);
            hero.add(new DeviceImage(), BorderLayout.EAST);
            addSpaced(page, hero, 0);

            JPanel setup = card();
            JPanel actions = new JPanel(new BorderLayout(12, 0));
            actions.setOpaque(false);
            port.setFont(new Font("Consolas", Font.PLAIN, 14));
            port.setBackground(WELL);
            port.setForeground(TEXT);
            port.setCaretColor(ACCENT);
            port.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                    BorderFactory.createEmptyBorder(9, 9, 9, 9)));
            port.getAccessibleContext().setAccessibleName("Puerto del emisor");
            actions.add(port, BorderLayout.CENTER);
            JLabel portLabel = label("Puerto", 13, MUTED, true);
            portLabel.setLabelFor(port);
            actions.add(portLabel, BorderLayout.WEST);
            actions.add(toggle, BorderLayout.EAST);
            JPanel identityInfo = column();
            addSpaced(identityInfo, label("Identidad de este equipo", 14, TEXT, true), 0);
            identityLabel.setFont(new Font("Consolas", Font.PLAIN, 15));
            addSpaced(identityInfo, identityLabel, 4);
            addSpaced(identityInfo, label("Compárala al vincular por primera vez.", 12, MUTED, false), 2);
            JPanel connectionSetup = column();
            addSpaced(connectionSetup, label("Emisor de Windows", 14, TEXT, true), 0);
            addSpaced(connectionSetup, actions, 7);
            JPanel setupColumns = grid(2);
            setupColumns.add(connectionSetup);
            setupColumns.add(identityInfo);
            addSpaced(setup, setupColumns, 0);
            addSpaced(page, setup, 10);
            JPanel available = grid(3);
            available.add(routeTile("Wi-Fi / LAN", lanState, 2, null));
            available.add(routeTile("Cable USB", usbState, 3, usbSection));
            available.add(routeTile("Otra red", vpnState, 0, vpnSection));
            addSpaced(page, available, 10);
            return page;
        }

        private JPanel routeTile(String title, JLabel status, int glyph, JComponent destination) {
            JPanel tile = new Surface(CARD, BORDER, 16);
            tile.setLayout(new BoxLayout(tile, BoxLayout.Y_AXIS));
            tile.setBorder(BorderFactory.createEmptyBorder(7, 8, 8, 8));
            JButton open = button(title, false);
            open.setIcon(new SectionIcon(glyph));
            open.setHorizontalAlignment(SwingConstants.LEADING);
            open.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            open.addActionListener(event -> {
                selectSection(2, true);
                if (destination != null) SwingUtilities.invokeLater(() -> destination.scrollRectToVisible(
                        new Rectangle(0, 0, destination.getWidth(), destination.getHeight())));
            });
            addSpaced(tile, open, 0);
            status.setBorder(BorderFactory.createEmptyBorder(0, 5, 1, 0));
            addSpaced(tile, status, 6);
            return tile;
        }

        private JPanel displayCard() {
            JPanel page = column();
            addSpaced(page, editorial("Pantallas e imagen", 27, TEXT), 0);
            JPanel targets = card();
            addSpaced(targets, label("Destino de la imagen", 16, TEXT, true), 0);
            addSpaced(targets, field("Escritorio remoto", desktop), 9);
            addSpaced(targets, field("Segundo monitor", secondary), 7);
            JPanel map = new StudioBanner();
            map.setLayout(new BorderLayout(0, 5));
            map.setBorder(BorderFactory.createEmptyBorder(10, 15, 11, 15));
            map.add(monitorMap, BorderLayout.CENTER);
            displayHint.setForeground(ON_INK);
            map.add(displayHint, BorderLayout.SOUTH);
            JPanel devices = grid(2);
            devices.add(targets);
            devices.add(map);
            addSpaced(page, devices, 12);
            JPanel prepare = grid(2);
            virtualSetup.addActionListener(event -> prepareMonitor());
            JButton extend = button("Extender escritorio", false);
            extend.addActionListener(event -> {
                try {
                    new ProcessBuilder(ConnectionSetup.windowsTool("DisplaySwitch.exe").toString(), "/extend").start();
                    showMessage("Windows está preparando el escritorio extendido.", false);
                } catch (IOException failure) { showMessage("Pulsa Win + P y elige Extender.", true); }
            });
            prepare.add(virtualSetup);
            prepare.add(extend);
            addSpaced(page, prepare, 9);
            JPanel transmission = card();
            addSpaced(transmission, label("Límites de transmisión", 16, TEXT, true), 0);
            JPanel limits = new JPanel(new GridLayout(1, 2, 10, 0));
            limits.setOpaque(false);
            limits.add(field("Resolución máxima", resolution));
            limits.add(field("Velocidad máxima", fps));
            addSpaced(transmission, limits, 9);
            JButton openSettings = button("Configuración de pantallas", false);
            openSettings.addActionListener(event -> {
                try { new ProcessBuilder("explorer.exe", "ms-settings:display").start(); }
                catch (IOException error) { showMessage("Abre Configuración → Sistema → Pantalla.", true); }
            });
            JButton resetLimits = button("Restablecer límites", false);
            resetLimits.addActionListener(event -> {
                resolution.setSelectedIndex(Wire.RESOLUTIONS.length - 1);
                fps.setSelectedIndex(Wire.FRAME_RATES.length - 1);
                showMessage("Límites restablecidos a 1080p y 60 FPS.", false);
            });
            JPanel buttons = grid(2);
            buttons.add(openSettings);
            buttons.add(resetLimits);
            addSpaced(transmission, buttons, 11);
            addSpaced(page, transmission, 10);
            return page;
        }

        private void openPage(String url) {
            try { Desktop.getDesktop().browse(URI.create(url)); }
            catch (IOException | UnsupportedOperationException failure) { showMessage("Abre " + url, true); }
        }

        private void prepareMonitor() {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Se abrirá Virtual Driver Control. Si hace falta, WinGet descargará la herramienta de VirtualDrivers.\n"
                    + "Elige el controlador de pantalla y pulsa Install. Windows puede pedirte permiso.\n"
                    + "Después vuelve aquí y pulsa Extender escritorio.", "Preparar monitor virtual",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
            virtualSetup.setEnabled(false);
            showMessage("Preparando Virtual Driver Control…", false);
            WindowsControl.daemon(() -> {
                try {
                    Desktop.getDesktop().open(ConnectionSetup.installControl().toFile());
                    SwingUtilities.invokeLater(() -> showMessage("En Virtual Driver Control, instala o activa el controlador de pantalla.", false));
                } catch (Exception failure) {
                    SwingUtilities.invokeLater(() -> {
                        if (!isDisplayable()) return;
                        showMessage("Virtual Driver Control está disponible en la página oficial.", true);
                        if (JOptionPane.showConfirmDialog(this, "WinGet no pudo abrir la herramienta. ¿Abrir la descarga oficial?",
                                "Monitor virtual", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) openPage(ConnectionSetup.DRIVER_PAGE);
                    });
                } finally { SwingUtilities.invokeLater(() -> virtualSetup.setEnabled(true)); }
            }, "prepare-monitor").start();
        }

        private void prepareCable() {
            if (server == null) { showMessage("Inicia el emisor para preparar el cable.", true); return; }
            HostServer current = server;
            usbSetup.setEnabled(false);
            WindowsControl.daemon(() -> {
                String message;
                boolean failed = false;
                try { message = ConnectionSetup.prepareUsb(current.port()); }
                catch (Exception failure) { message = failure instanceof IOException ? failure.getMessage() : "La preparación se interrumpió."; failed = true; }
                String result = message;
                boolean error = failed;
                SwingUtilities.invokeLater(() -> { usbSetup.setEnabled(server != null); if (server == current) showMessage(result, error); });
            }, "prepare-cable").start();
        }

        private JPanel networkCard() {
            JPanel page = column();
            addSpaced(page, editorial("Red y cable", 27, TEXT), 0);
            JPanel addresses = card();
            addSpaced(addresses, label("Direcciones de este equipo", 16, TEXT, true), 0);
            addSpaced(addresses, label("Copia una dirección para introducirla en Android.", 13, MUTED, false), 4);
            addSpaced(addresses, routes, 8);
            addSpaced(page, addresses, 11);
            addSpaced(usbSection, sectionTitle("Cable USB", 3), 0);
            addSpaced(usbSection, new WrapText("Conecta un cable de datos y activa Compartir Internet por USB en Android. La dirección aparecerá en la lista."), 4);
            usbSetup.setToolTipText("Para teléfonos con depuración USB autorizada y Android Platform Tools en Windows");
            usbSetup.setEnabled(false);
            usbSetup.addActionListener(event -> prepareCable());
            addSpaced(usbSection, usbSetup, 6);

            addSpaced(vpnSection, sectionTitle("Otra red", 2), 0);
            addSpaced(vpnSection, new WrapText("Abre Tailscale en ambos equipos. Copia la dirección de la PC y pégala en Conexión → Otra red en Android."), 4);
            JButton vpn = button("Obtener Tailscale", false);
            vpn.addActionListener(event -> openPage("https://tailscale.com/download/windows"));
            addSpaced(vpnSection, vpn, 6);
            JPanel setup = grid(2);
            setup.add(usbSection);
            setup.add(vpnSection);
            addSpaced(page, setup, 10);
            return page;
        }

        private void loadIdentity() {
            WindowsControl.daemon(() -> {
                try {
                    HostIdentity loaded = HostIdentity.load();
                    SwingUtilities.invokeLater(() -> {
                        identity = loaded;
                        identityLabel.setText(Wire.identityCode(loaded.fingerprint));
                        toggle.setEnabled(true);
                    });
                } catch (Exception error) {
                    SwingUtilities.invokeLater(() -> {
                        identityLabel.setText("Identidad no disponible");
                        showMessage("No se pudo cargar la identidad en el perfil de Windows. Cierra y abre el emisor.", true);
                    });
                }
            }, "pc-identity").start();
        }

        private void startServer() {
            int number;
            try {
                number = Integer.parseInt(port.getText().trim());
                if (number < 1024 || number > 65535) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                showMessage("Usa un puerto entre 1024 y 65535.", true);
                return;
            }
            try {
                int generation = ++serverGeneration;
                HostServer host = new HostServer(identity, number, computer, settings::get, controls, new HostServer.Events() {
                    @Override public void link(String value, boolean connected) {
                        SwingUtilities.invokeLater(() -> { if (server != null && serverGeneration == generation) setConnection(value, connected); });
                    }
                    @Override public void video(String value) {
                        SwingUtilities.invokeLater(() -> {
                            if (server == null || serverGeneration != generation) return;
                            video.setText(value);
                            video.setToolTipText(value);
                        });
                    }
                });
                server = host;
                host.start();
                preferences.putInt("port", number);
                String digits = host.pin();
                pin.setText(digits.substring(0, 3) + " " + digits.substring(3));
                copyPin.setEnabled(true);
                usbSetup.setEnabled(true);
                toggle.setText("Detener emisor");
                port.setEnabled(false);
                setConnection("Esperando Android", false);
                showMessage("Emisor listo. Abre Conexión en Android.", false);
            } catch (BindException error) {
                showMessage("Otro programa usa ese puerto. Cierra el emisor anterior o elige otro puerto.", true);
            } catch (IOException error) {
                showMessage("Windows no pudo abrir el puerto de conexión.", true);
            }
        }

        private void stopServer() {
            HostServer running = server;
            server = null;
            if (running != null) {
                toggle.setEnabled(false);
                // Keep the process alive until the session releases remote input and closes its sockets.
                new Thread(() -> {
                    try { running.close(); }
                    finally { SwingUtilities.invokeLater(() -> toggle.setEnabled(identity != null)); }
                }, "pc-stop").start();
            }
            setConnection("Emisor detenido", false);
            video.setText("Imagen en pausa");
            pin.setText("··· ···");
            port.setEnabled(true);
            usbSetup.setEnabled(false);
            toggle.setText("Iniciar emisor");
            copyPin.setEnabled(false);
            showMessage("El emisor está detenido.", false);
        }

        private void setConnection(String value, boolean connected) {
            connection.setToolTipText(value);
            connection.setText(connected ? "Android conectado" : value);
            connection.setForeground(connected ? ACCENT : MUTED);
        }

        private void refreshHardware() {
            Displays displays = Displays.read();
            String signature = displays.choices().toString() + displays.choices().stream().map(Displays.Choice::id).toList();
            if (!signature.equals(displaySignature)) {
                displaySignature = signature;
                loadingDisplays = true;
                String desiredDesktop = settings.get().desktop();
                String desiredSecondary = settings.get().secondary();
                desktop.removeAllItems();
                secondary.removeAllItems();
                desktop.addItem(new Option("", "Pantalla principal"));
                desktop.addItem(new Option("*", "Todas las pantallas"));
                secondary.addItem(new Option("", "Primera pantalla adicional"));
                for (Displays.Choice choice : displays.choices()) {
                    if (!choice.primary()) {
                        desktop.addItem(new Option(choice.id(), choice.name()));
                        secondary.addItem(new Option(choice.id(), choice.name()));
                    }
                }
                select(desktop, desiredDesktop);
                Displays.Choice resolved = displays.secondary(desiredSecondary);
                select(secondary, !desiredSecondary.isEmpty() && resolved != null ? resolved.id() : desiredSecondary);
                loadingDisplays = false;
                updateDisplayMap(displays);
            }
            List<HostNetwork.Route> active = HostNetwork.routes();
            if (!active.equals(currentRoutes) || routes.getComponentCount() == 0) {
                currentRoutes = active;
                routeStatus(lanState, "LAN", "Sin red local");
                routeStatus(usbState, "USB", "Conecta el cable");
                routeStatus(vpnState, "VPN", "Abre Tailscale");
                routes.removeAll();
                if (active.isEmpty()) addSpaced(routes, label("Conecta esta PC por Wi-Fi, Ethernet o USB.", 13, MUTED, false), 0);
                for (HostNetwork.Route route : active) {
                    String ip = route.address().getHostAddress();
                    JPanel row = new JPanel(new BorderLayout(10, 0));
                    row.setOpaque(false);
                    JPanel address = column();
                    addSpaced(address, label(route.label(), 12, MUTED, false), 0);
                    JLabel endpoint = label(ip, 16, TEXT, true);
                    endpoint.setFont(new Font("Consolas", Font.BOLD, 16));
                    addSpaced(address, endpoint, 2);
                    row.add(address, BorderLayout.CENTER);
                    JButton copy = button("Copiar dirección", false);
                    copy.addActionListener(event -> copy(ip + ":" + (server == null ? port.getText().trim() : server.port()), "Dirección y puerto copiados"));
                    row.add(copy, BorderLayout.EAST);
                    addSpaced(routes, row, 7);
                }
                routes.revalidate();
                routes.repaint();
            }
        }

        private void routeStatus(JLabel status, String type, String missing) {
            HostNetwork.Route best = currentRoutes.stream().filter(route -> route.type().equals(type)).findFirst().orElse(null);
            String ip = best == null ? null : best.address().getHostAddress();
            status.setText(ip == null ? missing : ip);
            status.setToolTipText(best == null ? missing : best.label() + " · " + ip);
            status.setForeground(ip == null ? MUTED : ACCENT);
        }

        private void updateDisplayMap(Displays displays) {
            Displays.Choice target = displays.secondary(settings.get().secondary());
            monitorMap.available(target != null);
            String hint = target != null ? target.name() : displays.choices().size() > 1
                    ? "Elige la pantalla adicional" : "Una pantalla activa";
            displayHint.setText(hint);
            displayHint.setToolTipText(hint);
        }

        private void updateSettings() {
            if (loadingDisplays || desktop.getSelectedItem() == null || secondary.getSelectedItem() == null) return;
            int quality = Math.max(0, resolution.getSelectedIndex());
            int rate = Math.max(0, fps.getSelectedIndex());
            settings.set(new Displays.Settings(((Option) desktop.getSelectedItem()).id,
                    ((Option) secondary.getSelectedItem()).id, quality, Wire.FRAME_RATES[rate]));
            preferences.put("desktop", settings.get().desktop());
            preferences.put("secondary", settings.get().secondary());
            preferences.putInt("quality", quality);
            preferences.putInt("rateIndex", rate);
            updateVideoLimit();
            updateDisplayMap(Displays.read());
        }

        private void updateVideoLimit() {
            Displays.Settings limit = settings.get();
            videoLimit.setText("Límite " + Wire.RESOLUTIONS[limit.quality()] + " · " + limit.fps() + " FPS");
        }

        private void copy(String value, String feedback) {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
                showMessage(feedback, false);
            } catch (IllegalStateException busy) { showMessage("El portapapeles está ocupado.", true); }
        }

        private void showMessage(String value, boolean error) {
            message.setText(value);
            message.setForeground(error ? AMBER : MUTED);
            message.setToolTipText(value);
        }

        private static void select(JComboBox<Option> control, String value) {
            for (int i = 0; i < control.getItemCount(); i++) {
                if (control.getItemAt(i).id.equals(value)) { control.setSelectedIndex(i); return; }
            }
            Option unavailable = new Option(value, "Pantalla elegida desconectada");
            control.addItem(unavailable);
            control.setSelectedItem(unavailable);
        }
    }

    private record Option(String id, String label) { @Override public String toString() { return label; } }

    private static JPanel field(String title, JComponent control) {
        JPanel panel = column();
        JLabel label = label(title, 12, MUTED, true);
        label.setLabelFor(control);
        addSpaced(panel, label, 0);
        addSpaced(panel, control, 3);
        return panel;
    }

    private static JLabel sectionTitle(String text, int symbol) {
        JLabel title = label(text, 16, TEXT, true);
        title.setIcon(new SectionIcon(symbol));
        title.setIconTextGap(8);
        return title;
    }

    private static JPanel grid(int columns) {
        JPanel row = new JPanel(new GridLayout(1, columns, 12, 0));
        row.setOpaque(false);
        return row;
    }

    private static JPanel inline() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        row.setOpaque(false);
        return row;
    }

    private static JPanel column() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JPanel card() {
        JPanel panel = new Surface(CARD, BORDER, 18);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 17, 16, 17));
        return panel;
    }

    private static void addSpaced(JPanel panel, JComponent child, int margin) {
        if (margin > 0) panel.add(Box.createVerticalStrut(margin));
        child.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (child instanceof JLabel) child.setMaximumSize(new Dimension(Integer.MAX_VALUE, child.getPreferredSize().height));
        panel.add(child);
    }

    private static JLabel label(String text, int size, Color color, boolean bold) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", bold ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(color);
        return label;
    }

    private static JButton button(String text, boolean accent) {
        return new StyledButton(text, accent, false);
    }

    private static JLabel editorial(String value, int size, Color color) {
        JLabel title = label(value, size, color, false);
        title.setFont(new Font("Segoe UI", Font.BOLD, size));
        return title;
    }

    private static BufferedImage mark(int size) {
        var icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.scale(size / 108.0, size / 108.0);
        graphics.setColor(INK);
        graphics.fillRoundRect(4, 4, 100, 100, 44, 44);
        Path2D top = new Path2D.Float();
        top.moveTo(20, 60); top.lineTo(36, 32); top.quadTo(39, 26, 46, 26);
        top.lineTo(77, 26); top.lineTo(69, 40); top.lineTo(48, 40); top.lineTo(32, 68); top.closePath();
        graphics.setColor(MINT);
        graphics.fill(top);
        Path2D bottom = new Path2D.Float();
        bottom.moveTo(88, 48); bottom.lineTo(72, 76); bottom.quadTo(69, 82, 62, 82);
        bottom.lineTo(31, 82); bottom.lineTo(39, 68); bottom.lineTo(60, 68); bottom.lineTo(76, 40); bottom.closePath();
        graphics.setColor(new Color(0x78AFFF));
        graphics.fill(bottom);
        graphics.dispose();
        return icon;
    }

    private static void writeIcon(Path file) throws IOException {
        int[] sizes = {16, 32, 48, 128, 256};
        byte[][] images = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(mark(sizes[i]), "png", png);
            images[i] = png.toByteArray();
        }
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeShort(0);
            output.writeShort(Short.reverseBytes((short) 1));
            output.writeShort(Short.reverseBytes((short) sizes.length));
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                output.writeByte(sizes[i]);
                output.writeByte(sizes[i]);
                output.writeShort(0);
                output.writeShort(Short.reverseBytes((short) 1));
                output.writeShort(Short.reverseBytes((short) 32));
                output.writeInt(Integer.reverseBytes(images[i].length));
                output.writeInt(Integer.reverseBytes(offset));
                offset += images[i].length;
            }
            for (byte[] png : images) output.write(png);
        }
    }

    private static boolean motionEnabled() {
        return !Boolean.FALSE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.menu.animation"))
                && !Boolean.TRUE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on"));
    }

    private static final class TabStrip extends JPanel {
        private int target;
        private float start, position;
        private long since;
        private final Timer motion = new Timer(16, event -> {
            float t = Math.min(1f, (System.nanoTime() - since) / 215_000_000f);
            float left = 1 - t;
            position = target + (start - target) * left * left * left;
            if (t >= 1) ((Timer) event.getSource()).stop();
            repaint();
        });

        TabStrip() { super(new GridLayout(1, 3, 4, 0)); setOpaque(false); }

        void select(int index, boolean animate) {
            target = index;
            motion.stop();
            if (animate && isShowing() && motionEnabled()) {
                start = position;
                since = System.nanoTime();
                motion.start();
            } else position = index;
            repaint();
        }

        @Override protected void paintChildren(Graphics source) {
            super.paintChildren(source);
            int first = (int) position;
            if (first < 0 || first >= getComponentCount()) return;
            Component a = getComponent(first), b = getComponent(Math.min(first + 1, getComponentCount() - 1));
            int x = a.getX() + Math.round((b.getX() - a.getX()) * (position - first));
            Graphics2D g = (Graphics2D) source.create();
            g.setColor(ACCENT);
            g.fillRoundRect(x + 10, getHeight() - 3, a.getWidth() - 20, 3, 3, 3);
            g.dispose();
        }
    }

    private static final class FieldCombo<E> extends JComboBox<E> {
        FieldCombo() { super(); }
        FieldCombo(E[] values) { super(values); }

        @Override public void updateUI() {
            setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
                @Override protected JButton createArrowButton() {
                    JButton arrow = new JButton("▾");
                    arrow.setBorder(BorderFactory.createEmptyBorder(1, 6, 2, 8));
                    arrow.setContentAreaFilled(false);
                    arrow.setForeground(ACCENT);
                    arrow.setFocusable(false);
                    return arrow;
                }
            });
            setFont(new Font("Segoe UI", Font.PLAIN, 13));
            setBackground(WELL);
            setForeground(TEXT);
            setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 2));
            setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int i, boolean selected, boolean focus) {
                    JLabel item = (JLabel) super.getListCellRendererComponent(list, value, i, selected, focus);
                    boolean highlight = selected && (i >= 0 || FieldCombo.this.hasFocus());
                    item.setBackground(highlight ? LIGHT[6] : WELL);
                    item.setForeground(highlight ? ON_INK : TEXT);
                    item.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
                    return item;
                }
            });
        }
    }

    private static final class WrapText extends JTextArea {
        WrapText(String text) {
            super(text);
            setEditable(false);
            setFocusable(false);
            setOpaque(false);
            setBorder(null);
            setLineWrap(true);
            setWrapStyleWord(true);
            setFont(new Font("Segoe UI", Font.PLAIN, 13));
            setForeground(MUTED);
        }

        @Override public Dimension getPreferredSize() {
            // BoxLayout asks for the height before the two columns receive their final widths.
            int width = getWidth() > 0 ? getWidth() : 214;
            var view = getUI().getRootView(this);
            view.setSize(width, Integer.MAX_VALUE);
            return new Dimension(width, (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + 3);
        }

        @Override public void setBounds(int x, int y, int width, int height) {
            boolean resized = width != getWidth();
            super.setBounds(x, y, width, height);
            if (resized) revalidate();
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private static final class ScrollColumn extends JPanel implements Scrollable {
        ScrollColumn() { super(new BorderLayout()); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return Math.max(20, r.height - 20); }
    }

    private static final class StudioBanner extends JPanel {
        StudioBanner() { setOpaque(false); }
        @Override protected void paintComponent(Graphics source) {
            Graphics2D g = (Graphics2D) source.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, getHeight(), INK, getWidth(), 0, new Color(0x235CA6)));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22);
            g.dispose();
            super.paintComponent(source);
        }
    }

    private static final class DeviceImage extends JComponent {
        private final BufferedImage devices;
        DeviceImage() {
            BufferedImage loaded = null;
            try (var stream = Main.class.getResourceAsStream("/studio_devices.png")) {
                if (stream != null) loaded = javax.imageio.ImageIO.read(stream);
            } catch (IOException ignored) { }
            devices = loaded;
            setPreferredSize(new Dimension(238, 146));
        }
        @Override protected void paintComponent(Graphics source) {
            if (devices == null) return;
            float scale = Math.min(getWidth() / (float) devices.getWidth(), getHeight() / (float) devices.getHeight());
            int width = Math.round(devices.getWidth() * scale);
            int height = Math.round(devices.getHeight() * scale);
            Graphics2D g = (Graphics2D) source.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(devices, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
            g.dispose();
        }
    }

    private static final class MonitorMap extends JComponent {
        private boolean available;
        MonitorMap() { setPreferredSize(new Dimension(200, 95)); }
        void available(boolean value) { if (value != available) { available = value; repaint(); } }
        @Override protected void paintComponent(Graphics source) {
            Graphics2D g = (Graphics2D) source.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float scale = Math.min(getWidth() / 250f, getHeight() / 100f);
            g.translate((getWidth() - 250 * scale) / 2, (getHeight() - 100 * scale) / 2);
            g.scale(scale, scale);
            g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0xB1DAFF));
            g.drawRoundRect(4, 10, 96, 55, 6, 6); g.drawLine(52, 65, 52, 74); g.drawLine(37, 75, 67, 75);
            g.setColor(available ? MINT : new Color(0x8EA7C1));
            g.drawLine(109, 42, 151, 42); g.drawLine(144, 36, 151, 42); g.drawLine(144, 48, 151, 42);
            g.drawRoundRect(161, 20, 83, 48, 6, 6); g.drawLine(194, 63, 210, 63);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            g.setColor(ON_INK);
            g.drawString("Windows", 28, 94); g.drawString("Android", 182, 94);
            g.dispose();
        }
    }

    private static final class Surface extends JPanel {
        private Color fill;
        private Color border;
        private final int radius;
        Surface(Color fill, Color border, int radius) { this.fill = fill; this.border = border; this.radius = radius; setOpaque(false); }
        @Override protected void paintComponent(Graphics source) {
            Graphics2D graphics = (Graphics2D) source.create();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(fill);
            graphics.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            graphics.setColor(border);
            graphics.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            graphics.dispose();
            super.paintComponent(source);
        }
    }

    private static final class MotionPane extends JPanel {
        private long started;
        private float progress = 1f;
        private final Timer motion = new Timer(16, event -> {
            progress = Math.min(1f, (System.nanoTime() - started) / 225_000_000f);
            if (progress >= 1f) ((Timer) event.getSource()).stop();
            repaint();
        });

        MotionPane(CardLayout layout) {
            super(layout);
            setOpaque(false);
        }

        void enter() {
            if (!motionEnabled()) { motion.stop(); progress = 1f; repaint(); return; }
            started = System.nanoTime();
            progress = 0f;
            motion.restart();
            repaint();
        }

        @Override protected void paintChildren(Graphics source) {
            if (progress >= 1f) {
                super.paintChildren(source);
                return;
            }
            Graphics2D graphics = (Graphics2D) source.create();
            float remaining = 1f - progress;
            graphics.setComposite(AlphaComposite.SrcOver.derive(1f - .8f * remaining * remaining));
            graphics.translate(0, Math.round(9 * remaining * remaining));
            super.paintChildren(graphics);
            graphics.dispose();
        }
    }

    private record SectionIcon(int section) implements Icon {
        @Override public int getIconWidth() { return 22; }
        @Override public int getIconHeight() { return 22; }
        @Override public void paintIcon(Component target, Graphics source, int x, int y) {
            Graphics2D g = (Graphics2D) source.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(target.getForeground());
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (section) {
                case 0 -> {
                    g.rotate(-.65, 11, 11);
                    g.drawRoundRect(1, 7, 9, 8, 5, 5); g.drawRoundRect(12, 7, 9, 8, 5, 5); g.drawLine(7, 11, 15, 11);
                }
                case 1 -> {
                    g.drawRoundRect(1, 3, 16, 12, 3, 3); g.drawLine(4, 18, 10, 18);
                    g.setColor(WELL); g.fillRoundRect(13, 9, 8, 12, 2, 2);
                    g.setColor(target.getForeground()); g.drawRoundRect(13, 9, 8, 12, 2, 2);
                }
                case 3 -> {
                    g.drawRoundRect(6, 7, 10, 7, 3, 3);
                    g.drawRect(8, 3, 6, 4);
                    g.drawArc(4, 10, 7, 10, 270, 90);
                }
                default -> {
                    g.drawOval(2, 2, 18, 18); g.drawOval(7, 2, 8, 18); g.drawLine(3, 11, 19, 11);
                }
            }
            g.dispose();
        }
    }

    private static final class StyledButton extends JButton {
        private final boolean accent;
        private final boolean tab;
        private final Timer motion;
        private float hover;

        StyledButton(String text, boolean accent, boolean tab) {
            super(text);
            this.accent = accent;
            this.tab = tab;
            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setForeground(accent ? ON_INK : TEXT);
            setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            motion = new Timer(16, event -> {
                float destination = getModel().isRollover() && isEnabled() ? 1f : 0f;
                hover += (destination - hover) * .36f;
                if (Math.abs(destination - hover) < .02f) { hover = destination; ((Timer) event.getSource()).stop(); }
                repaint();
            });
            getModel().addChangeListener(event -> {
                float target = getModel().isRollover() && isEnabled() ? 1f : 0f;
                if (!motionEnabled()) { hover = target; repaint(); }
                else if (isShowing() && target != hover) motion.start();
            });
        }

        @Override protected void paintComponent(Graphics source) {
            Graphics2D graphics = (Graphics2D) source.create();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = tab ? (getModel().isRollover() ? WELL : CARD) : accent && isEnabled() ? LIGHT[6] : WELL;
            int light = (int) (10 * hover) - (getModel().isPressed() ? 15 : 0);
            graphics.setColor(new Color(channel(base.getRed(), light), channel(base.getGreen(), light), channel(base.getBlue(), light)));
            graphics.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            if (isFocusOwner()) {
                graphics.setColor(accent ? ON_INK : ACCENT);
                graphics.setStroke(new BasicStroke(2));
                graphics.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 8, 8);
            }
            graphics.dispose();
            super.paintComponent(source);
        }

        private static int channel(int value, int light) { return Math.max(0, Math.min(255, value + light)); }
    }
}
