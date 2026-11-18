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
    private static final Color INK = new Color(0x291D3C);
    private static final Color[] LIGHT = palette(0xFDFBFC, 0xFFFFFF, 0xF5F0F4, 0xE4D8DE, 0x291D3C, 0x665968, 0x833A5C, 0x933F26);
    private static final Color[] DARK = palette(0x19151F, 0x29232F, 0x342D3C, 0x524559, 0xF8F1F5, 0xCCBBC9, 0xF2AFC5, 0xFFBF9F);
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

    private static final Color PEACH = new Color(0xFFB295);
    private static final Color ON_INK = new Color(0xFFF6EC);
    private static final Color SUBTLE_INK = new Color(0xE5CDD8);
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
        private final JLabel connection = label("Emisor detenido", 13, SUBTLE_INK, true);
        private final JLabel video = label("Imagen en pausa", 12, SUBTLE_INK, false);
        private final JLabel videoLimit = label("", 12, SUBTLE_INK, false);
        private final JLabel pin = label("··· ···", 38, ACCENT, true);
        private final JLabel identityLabel = label("Preparando identidad…", 12, MUTED, false);
        private final JLabel displayHint = label("Buscando pantallas en Windows…", 12, MUTED, false);
        private final JLabel message = label("Inicia el emisor para mostrar el código de conexión.", 13, MUTED, false);
        private final JButton toggle = button("Iniciar emisor", true);
        private final JButton copyPin = button("Copiar código", false);
        private final JTextField port = new JTextField(Integer.toString(
                Math.clamp(preferences.getInt("port", Wire.PORT), 1024, 65535)), 5);
        private final JComboBox<Option> desktop = new JComboBox<>();
        private final JComboBox<Option> secondary = new JComboBox<>();
        private final JComboBox<String> resolution = new JComboBox<>(Wire.RESOLUTIONS.clone());
        private final JComboBox<String> fps = new JComboBox<>(new String[]{"12 FPS", "20 FPS", "30 FPS", "45 FPS", "60 FPS"});
        private final JButton usbSetup = button("Preparar cable ADB", false);
        private final JButton virtualSetup = button("Preparar monitor virtual", true);
        private final JPanel routes = column();
        private final JLabel lanState = label("Buscando adaptador", 12, MUTED, false);
        private final JLabel usbState = label("Conecta el cable", 12, MUTED, false);
        private final JLabel vpnState = label("Abre Tailscale", 12, MUTED, false);
        private final JPanel usbSection = column();
        private final JPanel vpnSection = column();
        private final CardLayout layout = new CardLayout();
        private final MotionPane section = new MotionPane(layout);
        private final JButton[] navigation = new JButton[SECTIONS.length];
        private final AtomicReference<Displays.Settings> settings = new AtomicReference<>(new Displays.Settings(
                preferences.get("desktop", ""), preferences.get("secondary", ""),
                Math.clamp(preferences.getInt("quality", 3), 0, Wire.RESOLUTIONS.length - 1),
                Wire.FRAME_RATES[Math.clamp(preferences.getInt("rateIndex", 4), 0, Wire.FRAME_RATES.length - 1)]));
        private final JComboBox<String> appearance = new JComboBox<>(new String[]{"Sistema", "Blanco", "Oscuro"});
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
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setMinimumSize(new Dimension(660, 510));
            setSize(938, 610);
            setLocationByPlatform(true);
            setIconImage(mark(48));

            JPanel root = new JPanel(new BorderLayout(18, 0));
            root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 18));
            root.setBackground(BACKGROUND);
            setContentPane(root);
            root.add(sidebar(), BorderLayout.WEST);
            JPanel work = new JPanel(new BorderLayout(0, 12));
            work.setOpaque(false);
            section.add(page(pairingCard()), SECTIONS[0]);
            section.add(page(displayCard()), SECTIONS[1]);
            section.add(page(networkCard()), SECTIONS[2]);
            work.add(section, BorderLayout.CENTER);
            message.setBorder(BorderFactory.createEmptyBorder(5, 9, 3, 0));
            work.add(message, BorderLayout.SOUTH);
            root.add(work, BorderLayout.CENTER);
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
                @Override public void windowClosing(WindowEvent event) {
                    if (server == null) dispose();
                    else {
                        setExtendedState(getExtendedState() | Frame.ICONIFIED);
                        showMessage("El emisor sigue en la barra de tareas. Usa Salir para cerrarlo.", false);
                    }
                }
                @Override public void windowClosed(WindowEvent event) { refresh.stop(); section.motion.stop(); stopServer(); }
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

        private JPanel sidebar() {
            JPanel side = new Surface(INK, new Color(0x432A54), 24);
            side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
            side.setBorder(BorderFactory.createEmptyBorder(20, 16, 18, 16));
            side.setPreferredSize(new Dimension(211, 0));
            addSpaced(side, new JLabel(new ImageIcon(mark(59))), 0);
            addSpaced(side, editorial("Orbivía", 33, ON_INK), 8);
            JLabel name = label(computer, 12, SUBTLE_INK, false);
            name.setToolTipText(computer);
            addSpaced(side, name, 3);
            JSeparator divider = new JSeparator();
            divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            divider.setForeground(new Color(0x59466D));
            addSpaced(side, divider, 21);
            for (int i = 0; i < SECTIONS.length; i++) {
                int index = i;
                JButton tab = new StyledButton(SECTIONS[i], false, true);
                tab.setIcon(new SectionIcon(i));
                tab.setIconTextGap(10);
                tab.setHorizontalAlignment(SwingConstants.LEADING);
                tab.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
                tab.addActionListener(event -> selectSection(index, true));
                navigation[i] = tab;
                addSpaced(side, tab, i == 0 ? 11 : 5);
            }
            side.add(Box.createVerticalGlue());
            appearance.setSelectedIndex(Math.clamp(preferences.getInt("appearance", 0), 0, 2));
            appearance.getAccessibleContext().setAccessibleName("Apariencia");
            appearance.setMaximumSize(new Dimension(160, 30));
            appearance.addActionListener(event -> updateAppearance());
            addSpaced(side, label("Apariencia", 12, SUBTLE_INK, false), 8);
            addSpaced(side, appearance, 3);
            addSpaced(side, connection, 10);
            addSpaced(side, video, 4);
            addSpaced(side, videoLimit, 4);
            JButton exit = new StyledButton("Salir", false, true);
            exit.addActionListener(event -> dispose());
            addSpaced(side, exit, 8);
            return side;
        }

        private static JScrollPane page(JPanel contents) {
            JPanel stack = new JPanel(new BorderLayout());
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
            for (int i = 0; i < navigation.length; i++) navigation[i].setSelected(i == selected);
            layout.show(section, SECTIONS[selected]);
            if (animate) section.enter();
        }

        private JPanel pairingCard() {
            JPanel card = card();
            addSpaced(card, editorial("Vincular Android", 29, TEXT), 0);
            addSpaced(card, label("Inicia el emisor y abre Conexión en Android.", 12, MUTED, false), 6);
            JPanel codeWell = new Surface(INK, new Color(0x432A54), 17);
            codeWell.setLayout(new BorderLayout(10, 0));
            codeWell.setBorder(BorderFactory.createEmptyBorder(8, 13, 8, 10));
            pin.setFont(new Font("Consolas", Font.BOLD, 40));
            pin.setForeground(PEACH);
            codeWell.add(pin, BorderLayout.CENTER);
            codeWell.add(copyPin, BorderLayout.EAST);
            addSpaced(card, codeWell, 15);
            addSpaced(card, label("Identidad de este equipo", 12, TEXT, true), 13);
            identityLabel.setFont(new Font("Consolas", Font.PLAIN, 14));
            addSpaced(card, identityLabel, 4);
            addSpaced(card, label("Compara este texto la primera vez que conectes.", 12, MUTED, false), 4);

            JPanel actions = new JPanel(new BorderLayout(10, 0));
            actions.setOpaque(false);
            port.setFont(new Font("Consolas", Font.PLAIN, 14));
            port.setBackground(WELL);
            port.setForeground(TEXT);
            port.setCaretColor(ACCENT);
            port.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                    BorderFactory.createEmptyBorder(9, 9, 9, 9)));
            port.getAccessibleContext().setAccessibleName("Puerto del emisor");
            actions.add(field("Puerto", port), BorderLayout.WEST);
            actions.add(toggle, BorderLayout.CENTER);
            addSpaced(card, actions, 15);
            JPanel available = new JPanel(new GridLayout(1, 3, 8, 0));
            available.setOpaque(false);
            available.add(routeTile("Wi-Fi / LAN", lanState, 2, null));
            available.add(routeTile("Cable USB", usbState, 1, usbSection));
            available.add(routeTile("Otra red", vpnState, 0, vpnSection));
            addSpaced(card, available, 13);
            return card;
        }

        private JPanel routeTile(String title, JLabel status, int glyph, JComponent destination) {
            JPanel tile = new Surface(WELL, BORDER, 14);
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
            addSpaced(tile, status, 5);
            return tile;
        }

        private JPanel displayCard() {
            JPanel card = card();
            addSpaced(card, editorial("Pantallas e imagen", 29, TEXT), 0);
            addSpaced(card, field("Vista de escritorio", desktop), 10);
            addSpaced(card, field("Segundo monitor", secondary), 8);
            addSpaced(card, displayHint, 6);
            JPanel prepare = new JPanel(new GridLayout(1, 2, 7, 0));
            prepare.setOpaque(false);
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
            addSpaced(card, prepare, 8);
            JPanel limits = new JPanel(new GridLayout(1, 2, 10, 0));
            limits.setOpaque(false);
            limits.add(field("Resolución máxima", resolution));
            limits.add(field("Velocidad máxima", fps));
            addSpaced(card, limits, 10);
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
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
            buttons.setOpaque(false);
            buttons.add(openSettings);
            buttons.add(Box.createHorizontalStrut(8));
            buttons.add(resetLimits);
            addSpaced(card, buttons, 12);
            return card;
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
            JPanel card = card();
            addSpaced(card, editorial("Direcciones de red", 29, TEXT), 0);
            addSpaced(card, label("Usa la dirección correspondiente para una conexión manual.", 12, MUTED, false), 5);
            addSpaced(card, routes, 10);
            addSpaced(usbSection, label("Cable USB", 16, TEXT, true), 0);
            addSpaced(usbSection, label("<html>Conecta un cable de datos y activa Compartir Internet por USB en Android. La nueva dirección aparecerá arriba.</html>", 13, MUTED, false), 4);
            usbSetup.setToolTipText("Para teléfonos con depuración USB autorizada y Android Platform Tools en Windows");
            usbSetup.setEnabled(false);
            usbSetup.addActionListener(event -> prepareCable());
            addSpaced(usbSection, usbSetup, 6);
            addSpaced(card, usbSection, 15);
            addSpaced(vpnSection, label("Otra red", 16, TEXT, true), 0);
            addSpaced(vpnSection, label("<html>Conecta ambos equipos a Tailscale. Copia la dirección Tailscale y pégala en Conexión → Otra red en Android.</html>", 13, MUTED, false), 4);
            JButton vpn = button("Obtener Tailscale", false);
            vpn.addActionListener(event -> openPage("https://tailscale.com/download/windows"));
            addSpaced(vpnSection, vpn, 6);
            addSpaced(card, vpnSection, 13);
            return card;
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
                WindowsControl.daemon(() -> {
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
            connection.setForeground(connected ? PEACH : SUBTLE_INK);
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
                displayHint.setText(displays.secondary(desiredSecondary) == null
                        ? displaysHint(displays) : "Pantalla adicional lista · " + displays.secondary(desiredSecondary).name());
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
                    row.add(label(route.label() + "   ·   " + ip, 13, TEXT, true), BorderLayout.CENTER);
                    JButton copy = button("Copiar dirección", false);
                    copy.addActionListener(event -> copy(ip + ":" + (server == null ? port.getText().trim() : server.port()), "Dirección y puerto copiados"));
                    row.add(copy, BorderLayout.EAST);
                    addSpaced(routes, row, 5);
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

        private String displaysHint(Displays displays) {
            return displays.choices().size() > 1 ? "Elige una de las pantallas adicionales disponibles."
                    : "Una pantalla activa. Prepara un monitor virtual o conecta otra pantalla.";
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
            displayHint.setText(Displays.read().secondary(settings.get().secondary()) == null
                    ? "Sin pantalla adicional activa en Windows" : "Pantalla adicional disponible");
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
        title.setFont(new Font("Georgia", Font.PLAIN, size));
        return title;
    }

    private static BufferedImage mark(int size) {
        var icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.scale(size / 108.0, size / 108.0);
        graphics.setColor(INK);
        graphics.fillRoundRect(4, 4, 100, 100, 50, 50);
        Path2D ring = new Path2D.Float();
        ring.moveTo(71, 27);
        ring.curveTo(57, 19, 38, 23, 27, 36);
        ring.curveTo(16, 49, 19, 69, 31, 80);
        ring.curveTo(43, 91, 62, 90, 74, 79);
        ring.lineTo(64, 69);
        ring.curveTo(57, 76, 46, 76, 39, 69);
        ring.curveTo(32, 63, 31, 52, 38, 45);
        ring.curveTo(44, 38, 54, 36, 62, 41);
        ring.closePath();
        graphics.setColor(new Color(0xFFD8B4));
        graphics.fill(ring);
        Path2D band = new Path2D.Float();
        band.moveTo(27, 73);
        band.curveTo(35, 61, 50, 48, 69, 38);
        band.lineTo(76, 50);
        band.curveTo(58, 59, 45, 70, 38, 82);
        band.closePath();
        graphics.setColor(new Color(0xFF896D));
        graphics.fill(band);
        graphics.fillOval(69, 18, 18, 18);
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
            if (Boolean.FALSE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.menu.animation"))) return;
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
            graphics.translate(Math.round(12 * remaining * remaining), 0);
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
            g.setColor(PEACH);
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (section) {
                case 0 -> {
                    g.rotate(-.65, 11, 11);
                    g.drawRoundRect(1, 7, 9, 8, 5, 5); g.drawRoundRect(12, 7, 9, 8, 5, 5); g.drawLine(7, 11, 15, 11);
                }
                case 1 -> {
                    g.drawRoundRect(1, 3, 16, 12, 3, 3); g.drawLine(4, 18, 10, 18);
                    g.setColor(INK); g.fillRoundRect(13, 9, 8, 12, 2, 2);
                    g.setColor(PEACH); g.drawRoundRect(13, 9, 8, 12, 2, 2);
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
        private final boolean sidebar;
        private final Timer motion;
        private float hover;

        StyledButton(String text, boolean accent, boolean sidebar) {
            super(text);
            this.accent = accent;
            this.sidebar = sidebar;
            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setForeground(sidebar || accent ? ON_INK : TEXT);
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
            getModel().addChangeListener(event -> motion.start());
        }

        @Override protected void paintComponent(Graphics source) {
            Graphics2D graphics = (Graphics2D) source.create();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = sidebar ? isSelected() ? LIGHT[6] : INK : accent && isEnabled() ? LIGHT[6] : WELL;
            int light = (int) (18 * hover) - (getModel().isPressed() ? 13 : 0);
            graphics.setColor(new Color(channel(base.getRed(), light), channel(base.getGreen(), light), channel(base.getBlue(), light)));
            graphics.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 11, 11);
            graphics.setColor(isFocusOwner() ? (sidebar ? PEACH : TEXT) : BORDER);
            if ((!accent && !sidebar) || isFocusOwner()) graphics.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 11, 11);
            if (sidebar && isSelected()) {
                graphics.setColor(PEACH);
                graphics.fillRoundRect(3, 9, 3, getHeight() - 18, 3, 3);
            }
            graphics.dispose();
            super.paintComponent(source);
        }

        private static int channel(int value, int light) { return Math.max(0, Math.min(255, value + light)); }
    }
}
