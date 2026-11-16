package com.proyector.sender;

import com.proyector.shared.Wire;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.BindException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class Main {
    private static final Color BACKGROUND = new Color(15, 23, 34);
    private static final Color CARD = new Color(23, 35, 50);
    private static final Color WELL = new Color(31, 47, 64);
    private static final Color BORDER = new Color(51, 70, 89);
    private static final Color TEXT = new Color(239, 247, 253);
    private static final Color MUTED = new Color(170, 191, 207);
    private static final Color ACCENT = new Color(104, 229, 211);
    private static final Color AMBER = new Color(255, 198, 130);

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            UIManager.put("ComboBox.background", WELL);
            UIManager.put("ComboBox.foreground", TEXT);
            UIManager.put("ComboBox.selectionBackground", new Color(44, 80, 89));
            UIManager.put("ComboBox.selectionForeground", TEXT);
            UIManager.put("ComboBox.font", new Font("Segoe UI", Font.PLAIN, 13));
            new SenderWindow().setVisible(true);
        });
    }

    private static final class SenderWindow extends JFrame {
        private final String computer = Wire.displayName(System.getenv().getOrDefault("COMPUTERNAME", "Windows"));
        private final WindowsControl controls = new WindowsControl();
        private final JLabel connection = label("Emisor detenido", 13, MUTED, true);
        private final JLabel video = label("Imagen en pausa", 13, MUTED, false);
        private final JLabel pin = label("··· ···", 38, ACCENT, true);
        private final JLabel identityLabel = label("Preparando identidad…", 12, MUTED, false);
        private final JLabel displayHint = label("Buscando pantallas en Windows…", 12, MUTED, false);
        private final JLabel message = label("Inicia el emisor para mostrar el código de conexión.", 13, MUTED, false);
        private final JButton toggle = button("Iniciar emisor", true);
        private final JButton copyPin = button("Copiar código", false);
        private final JTextField port = new JTextField(Integer.toString(Wire.PORT), 5);
        private final JComboBox<Option> desktop = new JComboBox<>();
        private final JComboBox<Option> secondary = new JComboBox<>();
        private final JComboBox<String> resolution = new JComboBox<>(Wire.RESOLUTIONS.clone());
        private final JComboBox<String> fps = new JComboBox<>(new String[]{"12 FPS", "20 FPS", "30 FPS", "45 FPS", "60 FPS"});
        private final JPanel routes = column();
        private final AtomicReference<Displays.Settings> settings = new AtomicReference<>(new Displays.Settings("", "", 3, 60));
        private HostIdentity identity;
        private HostServer server;
        private int serverGeneration;
        private boolean loadingDisplays;
        private String displaySignature = "";
        private List<HostNetwork.Route> currentRoutes = List.of();
        private final Timer refresh;

        SenderWindow() {
            super("Control de PC · Emisor");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setMinimumSize(new Dimension(720, 515));
            setSize(1020, 730);
            setLocationByPlatform(true);
            setIconImage(mark(48));

            JPanel root = new JPanel(new BorderLayout(0, 14));
            root.setBorder(BorderFactory.createEmptyBorder(19, 22, 16, 22));
            root.setBackground(BACKGROUND);
            setContentPane(root);
            root.add(header(), BorderLayout.NORTH);

            JPanel stack = column();
            JPanel twoColumns = new JPanel(new GridLayout(1, 2, 14, 0));
            twoColumns.setOpaque(false);
            twoColumns.add(pairingCard());
            twoColumns.add(displayCard());
            add(stack, twoColumns, 0);
            add(stack, networkCard(), 14);
            JScrollPane scroll = new JScrollPane(stack);
            scroll.setBorder(null);
            scroll.getViewport().setBackground(BACKGROUND);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(18);
            root.add(scroll, BorderLayout.CENTER);
            root.add(message, BorderLayout.SOUTH);

            toggle.setEnabled(false);
            toggle.addActionListener(event -> { if (server == null) startServer(); else stopServer(); });
            copyPin.setEnabled(false);
            copyPin.addActionListener(event -> { if (server != null) copy(server.pin(), "Código copiado"); });
            desktop.addActionListener(event -> updateSettings());
            secondary.addActionListener(event -> updateSettings());
            resolution.setSelectedIndex(3);
            fps.setSelectedIndex(4);
            resolution.addActionListener(event -> updateSettings());
            fps.addActionListener(event -> updateSettings());
            refresh = new Timer(1600, event -> refreshHardware());
            refreshHardware();
            refresh.start();
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { refresh.stop(); stopServer(); }
            });
            loadIdentity();
        }

        private JPanel header() {
            JPanel header = new JPanel(new BorderLayout(12, 0));
            header.setOpaque(false);
            header.add(new JLabel(new ImageIcon(mark(44))), BorderLayout.WEST);
            JPanel title = column();
            add(title, label("Control de PC", 25, TEXT, true), 0);
            add(title, label(computer + "  ·  Emisor de Windows", 13, MUTED, false), 3);
            header.add(title, BorderLayout.CENTER);
            JPanel session = column();
            add(session, connection, 2);
            add(session, video, 4);
            header.add(session, BorderLayout.EAST);
            return header;
        }

        private JPanel pairingCard() {
            JPanel card = card();
            add(card, label("Vincular Android", 20, TEXT, true), 0);
            add(card, label("Abre Conexión en el teléfono y escribe el código.", 12, MUTED, false), 6);
            JPanel codeWell = new Surface(WELL, BORDER, 15);
            codeWell.setLayout(new BorderLayout(10, 0));
            codeWell.setBorder(BorderFactory.createEmptyBorder(8, 13, 8, 10));
            pin.setFont(new Font("Consolas", Font.BOLD, 40));
            codeWell.add(pin, BorderLayout.CENTER);
            codeWell.add(copyPin, BorderLayout.EAST);
            add(card, codeWell, 15);
            add(card, label("Identidad de este equipo", 12, TEXT, true), 13);
            identityLabel.setFont(new Font("Consolas", Font.PLAIN, 14));
            add(card, identityLabel, 4);
            add(card, label("Compara este texto la primera vez que conectes.", 12, MUTED, false), 4);

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
            add(card, actions, 15);
            return card;
        }

        private JPanel displayCard() {
            JPanel card = card();
            add(card, label("Pantallas e imagen", 20, TEXT, true), 0);
            add(card, field("Vista de escritorio", desktop), 10);
            add(card, field("Segundo monitor", secondary), 8);
            add(card, displayHint, 6);
            JPanel limits = new JPanel(new GridLayout(1, 2, 10, 0));
            limits.setOpaque(false);
            limits.add(field("Resolución máxima", resolution));
            limits.add(field("Velocidad máxima", fps));
            add(card, limits, 10);
            JButton openSettings = button("Configuración de pantallas", false);
            openSettings.addActionListener(event -> {
                try { new ProcessBuilder("explorer.exe", "ms-settings:display").start(); }
                catch (IOException error) { showMessage("Abre Configuración → Sistema → Pantalla.", true); }
            });
            add(card, openSettings, 12);
            return card;
        }

        private JPanel networkCard() {
            JPanel card = card();
            add(card, label("Conexión", 20, TEXT, true), 0);
            add(card, label("Usa la dirección correspondiente para una conexión manual.", 12, MUTED, false), 5);
            add(card, routes, 10);
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
                        SwingUtilities.invokeLater(() -> { if (server != null && serverGeneration == generation) video.setText(value); });
                    }
                });
                server = host;
                host.start();
                String digits = host.pin();
                pin.setText(digits.substring(0, 3) + " " + digits.substring(3));
                copyPin.setEnabled(true);
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
            toggle.setText("Iniciar emisor");
            copyPin.setEnabled(false);
            showMessage("El emisor está detenido.", false);
        }

        private void setConnection(String value, boolean connected) {
            connection.setText(value);
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
                select(secondary, desiredSecondary);
                loadingDisplays = false;
                displayHint.setText(displays.secondary(desiredSecondary) == null
                        ? "Sin pantalla adicional activa en Windows" : "Pantalla adicional disponible");
            }
            List<HostNetwork.Route> active = HostNetwork.routes();
            if (!active.equals(currentRoutes) || routes.getComponentCount() == 0) {
                currentRoutes = active;
                routes.removeAll();
                if (active.isEmpty()) add(routes, label("Conecta esta PC por Wi-Fi, Ethernet o USB.", 13, MUTED, false), 0);
                for (HostNetwork.Route route : active) {
                    String ip = route.address().getHostAddress();
                    JPanel row = new JPanel(new BorderLayout(10, 0));
                    row.setOpaque(false);
                    row.add(label(route.label() + "   ·   " + ip, 13, TEXT, true), BorderLayout.CENTER);
                    JButton copy = button("Copiar IP", false);
                    copy.addActionListener(event -> copy(ip, "IP copiada"));
                    row.add(copy, BorderLayout.EAST);
                    add(routes, row, 5);
                }
                routes.revalidate();
                routes.repaint();
            }
        }

        private void updateSettings() {
            if (loadingDisplays || desktop.getSelectedItem() == null || secondary.getSelectedItem() == null) return;
            int quality = Math.max(0, resolution.getSelectedIndex());
            int rate = Math.max(0, fps.getSelectedIndex());
            settings.set(new Displays.Settings(((Option) desktop.getSelectedItem()).id,
                    ((Option) secondary.getSelectedItem()).id, quality, Wire.FRAME_RATES[rate]));
            displayHint.setText(Displays.read().secondary(settings.get().secondary()) == null
                    ? "Sin pantalla adicional activa en Windows" : "Pantalla adicional disponible");
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
        add(panel, label, 0);
        add(panel, control, 3);
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

    private static void add(JPanel panel, JComponent child, int margin) {
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
        JButton button = new AnimatedButton(text, accent);
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        button.setForeground(accent ? BACKGROUND : TEXT);
        button.setBorder(BorderFactory.createEmptyBorder(9, 11, 9, 11));
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        return button;
    }

    private static java.awt.image.BufferedImage mark(int size) {
        var icon = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.scale(size / 44.0, size / 44.0);
        graphics.setColor(new Color(33, 67, 73));
        graphics.fillRoundRect(0, 0, 44, 44, 15, 15);
        graphics.setColor(ACCENT);
        graphics.setStroke(new BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.drawRoundRect(8, 10, 22, 15, 4, 4);
        graphics.drawLine(15, 30, 23, 30);
        graphics.drawLine(19, 25, 19, 30);
        graphics.setColor(new Color(33, 67, 73));
        graphics.fillRoundRect(24, 18, 12, 17, 4, 4);
        graphics.setColor(TEXT);
        graphics.drawRoundRect(24, 18, 12, 17, 4, 4);
        graphics.dispose();
        return icon;
    }

    private static final class Surface extends JPanel {
        private final Color fill;
        private final Color border;
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

    private static final class AnimatedButton extends JButton {
        private final boolean accent;
        private final Timer motion;
        private float hover;

        AnimatedButton(String text, boolean accent) {
            super(text);
            this.accent = accent;
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
            Color base = accent && isEnabled() ? ACCENT : WELL;
            int light = (int) (18 * hover) - (getModel().isPressed() ? 13 : 0);
            graphics.setColor(new Color(channel(base.getRed(), light), channel(base.getGreen(), light), channel(base.getBlue(), light)));
            graphics.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 11, 11);
            graphics.setColor(isFocusOwner() ? TEXT : BORDER);
            if (!accent || isFocusOwner()) graphics.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 11, 11);
            graphics.dispose();
            super.paintComponent(source);
        }

        private static int channel(int value, int light) { return Math.max(0, Math.min(255, value + light)); }
    }
}
