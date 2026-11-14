package com.proyector.sender;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.BiConsumer;

public final class Main {
    private static final int DISCOVERY_PORT = 5051;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SenderWindow().setVisible(true));
    }

    private static final class SenderWindow extends JFrame {
        private final JTextField portField = new JTextField("5050", 6);
        private final JComboBox<String> qualityBox = new JComboBox<>(new String[]{
                "Baja · hasta 360p", "Equilibrada · hasta 540p",
                "Alta · hasta 720p", "Full HD · hasta 1080p"
        });
        private final JComboBox<String> fpsBox = new JComboBox<>(new String[]{
                "8 FPS · ahorro", "15 FPS · estable", "24 FPS · suave",
                "30 FPS · fluido", "45 FPS · rápido", "60 FPS · máximo"
        });
        private final JComboBox<String> screenBox = new JComboBox<>();
        private final List<String> screenDeviceIds = new ArrayList<>();
        private final JLabel status = new JLabel("Listo para iniciar");
        private final JLabel pairingCode;
        private final JPanel addresses = new JPanel();
        private final SmoothButton toggle = new SmoothButton("Iniciar emisor", new Color(18, 107, 218));
        private final Map<String, AuthAttempts> failedPins = new HashMap<>();
        private ServerSocket server;
        private volatile boolean running;
        private String addressSignature = "";
        private String pairingPin = newPairingPin();

        SenderWindow() {
            super("Control de PC · Proyector");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setMinimumSize(new Dimension(860, 590));
            setSize(1040, 690);
            setLocationByPlatform(true);

            JPanel root = new JPanel(new java.awt.BorderLayout(18, 18));
            root.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
            root.setBackground(new Color(8, 20, 43));
            setContentPane(root);

            JPanel hero = new JPanel(new java.awt.BorderLayout(18, 0));
            hero.setOpaque(false);
            JPanel headings = new JPanel();
            headings.setOpaque(false);
            headings.setLayout(new javax.swing.BoxLayout(headings, javax.swing.BoxLayout.Y_AXIS));
            headings.add(label("CONTROL DE PC  /  WINDOWS", new Color(63, 180, 255), 11, true));
            headings.add(BoxGap.vertical(6));
            headings.add(label("Tu computadora, siempre cerca", Color.WHITE, 26, true));
            headings.add(BoxGap.vertical(5));
            headings.add(label("Proyecta y controla Windows por red local, USB o VPN privada.",
                    new Color(182, 201, 230), 13, false));
            hero.add(headings, java.awt.BorderLayout.CENTER);
            String computer = System.getenv("COMPUTERNAME");
            JLabel computerLabel = label("  " + (computer == null || computer.isEmpty() ? "Windows" : computer) + "  ",
                    new Color(161, 214, 255), 13, true);
            computerLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(57, 99, 151)),
                    BorderFactory.createEmptyBorder(9, 10, 9, 10)));
            hero.add(computerLabel, java.awt.BorderLayout.EAST);
            root.add(hero, java.awt.BorderLayout.NORTH);

            JPanel workspace = new JPanel(new java.awt.BorderLayout(18, 0));
            workspace.setOpaque(false);
            root.add(workspace, java.awt.BorderLayout.CENTER);

            RoundedPanel guide = new RoundedPanel(new Color(16, 42, 80), 18);
            guide.setPreferredSize(new Dimension(246, 480));
            guide.setBorder(BorderFactory.createEmptyBorder(20, 18, 18, 18));
            guide.setLayout(new javax.swing.BoxLayout(guide, javax.swing.BoxLayout.Y_AXIS));
            guide.add(label("GUÍA RÁPIDA", new Color(63, 180, 255), 12, true));
            guide.add(BoxGap.vertical(18));
            addGuideStep(guide, "01", "Inicia el emisor", "Pulsa el botón azul<br>en esta PC.");
            addGuideStep(guide, "02", "Escribe el código", "Introduce los cuatro<br>dígitos en Android.");
            addGuideStep(guide, "03", "Abre Remoto", "Verás el escritorio<br>en el teléfono.");
            guide.add(label("PANTALLA A PROYECTAR", new Color(63, 180, 255), 11, true));
            guide.add(BoxGap.vertical(8));
            populateScreenChoices();
            styleCombo(screenBox);
            screenBox.setMaximumSize(new Dimension(207, 30));
            screenBox.setAlignmentX(LEFT_ALIGNMENT);
            guide.add(screenBox);
            guide.add(BoxGap.vertical(7));
            guide.add(label("Principal permite 1080p real.", new Color(170, 192, 225), 11, false));
            guide.add(javax.swing.Box.createVerticalGlue());
            guide.add(label("Wi-Fi o USB · red privada", new Color(170, 192, 225), 11, false));
            workspace.add(guide, java.awt.BorderLayout.WEST);

            JPanel main = new JPanel();
            main.setOpaque(false);
            main.setLayout(new javax.swing.BoxLayout(main, javax.swing.BoxLayout.Y_AXIS));
            javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(main);
            scroll.setBorder(null);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(14);
            workspace.add(scroll, java.awt.BorderLayout.CENTER);

            RoundedPanel networkCard = new RoundedPanel(new Color(25, 49, 87), 18);
            networkCard.setLayout(new javax.swing.BoxLayout(networkCard, javax.swing.BoxLayout.Y_AXIS));
            networkCard.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
            networkCard.add(label("DIRECCIONES DE ESTA PC", Color.WHITE, 12, true));
            networkCard.add(BoxGap.vertical(5));
            networkCard.add(label("La APK encuentra esta PC aunque cambie su IP.",
                    new Color(182, 201, 230), 12, false));
            networkCard.add(BoxGap.vertical(12));
            addresses.setOpaque(false);
            addresses.setLayout(new javax.swing.BoxLayout(addresses, javax.swing.BoxLayout.Y_AXIS));
            addresses.setAlignmentX(LEFT_ALIGNMENT);
            networkCard.add(addresses);
            networkCard.setAlignmentX(LEFT_ALIGNMENT);
            main.add(networkCard);
            main.add(BoxGap.vertical(14));

            RoundedPanel settingsCard = new RoundedPanel(new Color(22, 44, 80), 18);
            settingsCard.setLayout(new javax.swing.BoxLayout(settingsCard, javax.swing.BoxLayout.Y_AXIS));
            settingsCard.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
            settingsCard.add(label("VINCULACIÓN", Color.WHITE, 12, true));
            settingsCard.add(BoxGap.vertical(10));
            pairingCode = label("CÓDIGO  " + pairingPin, new Color(63, 180, 255), 23, true);
            settingsCard.add(pairingCode);
            settingsCard.add(BoxGap.vertical(6));
            settingsCard.add(label("Escríbelo en la APK para conectar tus dispositivos.",
                    new Color(182, 201, 230), 12, false));
            settingsCard.add(BoxGap.vertical(18));
            JPanel settings = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 9, 0));
            settings.setOpaque(false);
            settings.setAlignmentX(LEFT_ALIGNMENT);
            settings.add(label("Puerto", new Color(223, 234, 250), 13, false));
            styleField(portField);
            settings.add(portField);
            settings.add(BoxGap.horizontal(9));
            settings.add(label("Calidad", new Color(223, 234, 250), 13, false));
            styleCombo(qualityBox);
            settings.add(qualityBox);
            settings.add(BoxGap.horizontal(9));
            settings.add(label("FPS", new Color(223, 234, 250), 13, false));
            styleCombo(fpsBox);
            settings.add(fpsBox);
            settingsCard.add(settings);
            settingsCard.setAlignmentX(LEFT_ALIGNMENT);
            main.add(settingsCard);
            main.add(BoxGap.vertical(14));

            toggle.setFocusPainted(false);
            toggle.setForeground(Color.WHITE);
            toggle.setFont(new Font("Segoe UI", Font.BOLD, 15));
            toggle.setBorder(BorderFactory.createEmptyBorder(13, 22, 13, 22));
            toggle.addActionListener(event -> { if (running) stopServer(); else startServer(); });
            toggle.setAlignmentX(LEFT_ALIGNMENT);
            main.add(toggle);
            main.add(BoxGap.vertical(14));
            RoundedPanel feedbackCard = new RoundedPanel(new Color(16, 42, 80), 16);
            feedbackCard.setLayout(new javax.swing.BoxLayout(feedbackCard, javax.swing.BoxLayout.Y_AXIS));
            feedbackCard.setBorder(BorderFactory.createEmptyBorder(13, 17, 13, 17));
            feedbackCard.add(label("ESTADO DEL EMISOR", new Color(63, 180, 255), 11, true));
            feedbackCard.add(BoxGap.vertical(5));
            status.setForeground(new Color(182, 201, 230));
            status.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            status.setAlignmentX(LEFT_ALIGNMENT);
            feedbackCard.add(status);
            feedbackCard.setAlignmentX(LEFT_ALIGNMENT);
            main.add(feedbackCard);

            Timer refresh = new Timer(1600, event -> refreshAddresses());
            refresh.start();
            refreshAddresses();
        }

        private void addGuideStep(JPanel guide, String number, String title, String detail) {
            JPanel block = new JPanel();
            block.setOpaque(false);
            block.setLayout(new javax.swing.BoxLayout(block, javax.swing.BoxLayout.Y_AXIS));
            block.setAlignmentX(LEFT_ALIGNMENT);
            block.add(label(number + "  " + title, Color.WHITE, 14, true));
            block.add(BoxGap.vertical(4));
            block.add(label("<html>" + detail + "</html>",
                    new Color(170, 192, 225), 12, false));
            guide.add(block);
            guide.add(BoxGap.vertical(22));
        }

        private static JLabel label(String text, Color color, int size, boolean bold) {
            JLabel result = new JLabel(text);
            result.setForeground(color);
            result.setFont(new Font("Segoe UI", bold ? Font.BOLD : Font.PLAIN, size));
            result.setAlignmentX(LEFT_ALIGNMENT);
            return result;
        }

        private void refreshAddresses() {
            List<AddressInfo> current = localAddresses();
            String signature = current.toString();
            if (signature.equals(addressSignature)) return;
            addressSignature = signature;
            addresses.removeAll();
            if (current.isEmpty()) {
                addresses.add(label("Conecta la PC a Wi‑Fi, Ethernet o USB para ver la IP.", new Color(245, 188, 84), 12, false));
            } else {
                for (AddressInfo info : current) {
                    JPanel row = new JPanel(new java.awt.BorderLayout(12, 0));
                    row.setOpaque(false);
                    row.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
                    JLabel text = label(info.name + "     " + info.address, new Color(244, 248, 255), 13, true);
                    row.add(text, java.awt.BorderLayout.CENTER);
                    JButton copy = new JButton("Copiar IP");
                    styleSmallButton(copy);
                    copy.addActionListener(event -> {
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(info.address), null);
                        setStatus("IP copiada: " + info.address);
                    });
                    row.add(copy, java.awt.BorderLayout.EAST);
                    addresses.add(row);
                }
            }
            addresses.revalidate();
            addresses.repaint();
        }

        private void startServer() {
            final int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
                if (port < 1024 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                setStatus("Puerto no válido: usa un número entre 1024 y 65535.");
                return;
            }

            try {
                server = new ServerSocket(port, 5, InetAddress.getByName("0.0.0.0"));
                pairingPin = newPairingPin();
                pairingCode.setText("CÓDIGO  " + pairingPin);
                running = true;
                toggle.setText("Detener emisor");
                toggle.setBackground(new Color(49, 75, 116));
                portField.setEnabled(false);
                qualityBox.setEnabled(false);
            fpsBox.setEnabled(false);
                screenBox.setEnabled(false);
                setStatus("Emisor activo. Buscando receptores enlazados…");
                Thread accept = new Thread(() -> acceptClients(), "proyector-accept");
                accept.setDaemon(true);
                accept.start();
                Thread discovery = new Thread(() -> broadcastDiscovery(port), "proyector-discovery");
                discovery.setDaemon(true);
                discovery.start();
            } catch (IOException error) {
                setStatus("No se pudo abrir el puerto: " + error.getMessage());
            }
        }

        private void acceptClients() {
            while (running) {
                try (Socket client = server.accept()) {
                    if (!isAllowedRemoteAddress(client.getInetAddress())) {
                        setStatus("Se rechazó una conexión fuera de la red local.");
                        continue;
                    }
                    client.setTcpNoDelay(true);
                    client.setKeepAlive(true);
                    String remoteAddress = client.getInetAddress().getHostAddress();
                    AuthAttempts attempts = failedPins.get(remoteAddress);
                    if (attempts != null && attempts.blockedUntil > System.currentTimeMillis()) {
                        new DataOutputStream(client.getOutputStream()).writeInt(-1);
                        setStatus("Demasiados códigos incorrectos desde " + remoteAddress + ". Espera un minuto.");
                        continue;
                    }
                    client.setSoTimeout(12000);
                    DataInputStream input = new DataInputStream(new BufferedInputStream(client.getInputStream()));
                    String suppliedPin = input.readUTF();
                    if (!samePin(pairingPin, suppliedPin)) {
                        if (attempts == null) attempts = new AuthAttempts();
                        attempts.failures++;
                        if (attempts.failures >= 5) {
                            attempts.failures = 0;
                            attempts.blockedUntil = System.currentTimeMillis() + 60000;
                        }
                        failedPins.put(remoteAddress, attempts);
                        new DataOutputStream(client.getOutputStream()).writeInt(-1);
                        setStatus("Se rechazó una conexión: el código no coincide.");
                        continue;
                    }
                    failedPins.remove(remoteAddress);
                    client.setSoTimeout(0);
                    new DataOutputStream(client.getOutputStream()).writeInt(0);
                    setStatus("Receptor enlazado: " + client.getInetAddress().getHostAddress());
                    new StreamSession(client, input, qualityBox.getSelectedIndex(),
                            selectedScreenDeviceId(), fpsFromSelection(), this::setStatus).run();
                    if (running) setStatus("Receptor desconectado. Esperando otra conexión…");
                } catch (IOException error) {
                    if (running) setStatus("Conexión cerrada: " + error.getMessage());
                }
            }
        }

        private void broadcastDiscovery(int port) {
            try (DatagramSocket discovery = new DatagramSocket()) {
                discovery.setBroadcast(true);
                byte[] message = ("PROYECTOR/1:" + port).getBytes(StandardCharsets.UTF_8);
                while (running) {
                    List<InetAddress> destinations = broadcastAddresses();
                    destinations.add(InetAddress.getByName("255.255.255.255"));
                    for (InetAddress target : destinations) {
                        try {
                            discovery.send(new DatagramPacket(message, message.length, target, DISCOVERY_PORT));
                        } catch (IOException ignored) { }
                    }
                    Thread.sleep(900);
                }
            } catch (Exception error) {
                if (running) setStatus("La búsqueda automática tuvo un problema: " + error.getMessage());
            }
        }

        private void stopServer() {
            running = false;
            try { if (server != null) server.close(); } catch (IOException ignored) { }
            toggle.setText("Iniciar emisor");
            toggle.setBackground(new Color(18, 107, 218));
            portField.setEnabled(true);
            qualityBox.setEnabled(true);
            fpsBox.setEnabled(true);
            screenBox.setEnabled(true);
            setStatus("Emisor detenido");
        }

        private void setStatus(String message) {
            SwingUtilities.invokeLater(() -> status.setText("<html><div style='width:420px'>"
                    + message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    + "</div></html>"));
        }

        private void populateScreenChoices() {
            screenDeviceIds.clear();
            screenBox.removeAllItems();
            GraphicsDevice primary = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            screenDeviceIds.add(primary.getIDstring());
            screenBox.addItem("Pantalla principal");
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                if (device.getIDstring().equals(primary.getIDstring())) continue;
                screenDeviceIds.add(device.getIDstring());
                screenBox.addItem("Monitor " + screenDeviceIds.size());
            }
            screenBox.addItem("Todas las pantallas");
            screenBox.setSelectedIndex(0);
        }

        private String selectedScreenDeviceId() {
            int selection = screenBox.getSelectedIndex();
            if (selection < 0 || selection >= screenDeviceIds.size()) return null;
            return screenDeviceIds.get(selection);
        }

        private int fpsFromSelection() {
            int selection = Math.max(0, fpsBox.getSelectedIndex());
            int[] values = {8, 15, 24, 30, 45, 60};
            return values[Math.min(selection, values.length - 1)];
        }
    }

    private static final class StreamSession {
        private final Socket socket;
        private final DataInputStream input;
        private final int qualityIndex;
        private final String captureDeviceId;
        private final int targetFps;
        private final StatusListener status;
        private final BiConsumer<Integer, BiConsumer<Boolean, String>> actionHandler;
        private volatile DataOutputStream output;

        StreamSession(Socket socket, DataInputStream input, int qualityIndex, StatusListener status) {
            this(socket, input, qualityIndex, null, 0, status,
                    (command, result) -> WindowsAction.execute(command, status, result));
        }

        StreamSession(Socket socket, DataInputStream input, int qualityIndex, StatusListener status,
                      BiConsumer<Integer, BiConsumer<Boolean, String>> actionHandler) {
            this(socket, input, qualityIndex, false, status, actionHandler);
        }

        StreamSession(Socket socket, DataInputStream input, int qualityIndex, boolean captureAllScreens,
                      StatusListener status) {
            this(socket, input, qualityIndex, captureAllScreens ? null : primaryDeviceId(), 0, status,
                    (command, result) -> WindowsAction.execute(command, status, result));
        }

        StreamSession(Socket socket, DataInputStream input, int qualityIndex, boolean captureAllScreens,
                      StatusListener status, BiConsumer<Integer, BiConsumer<Boolean, String>> actionHandler) {
            this(socket, input, qualityIndex, captureAllScreens ? null : primaryDeviceId(), 0, status, actionHandler);
        }

        StreamSession(Socket socket, DataInputStream input, int qualityIndex, String captureDeviceId,
                      int targetFps, StatusListener status) {
            this(socket, input, qualityIndex, captureDeviceId, targetFps, status,
                    (command, result) -> WindowsAction.execute(command, status, result));
        }

        StreamSession(Socket socket, DataInputStream input, int qualityIndex, String captureDeviceId,
                      int targetFps, StatusListener status,
                      BiConsumer<Integer, BiConsumer<Boolean, String>> actionHandler) {
            this.socket = socket;
            this.input = input;
            this.qualityIndex = qualityIndex;
            this.captureDeviceId = captureDeviceId;
            this.targetFps = targetFps;
            this.status = status;
            this.actionHandler = actionHandler;
        }

        void run() {
            try {
                boolean captureAllScreens = captureDeviceId == null;
                GraphicsDevice device = captureAllScreens ? null : findDevice(captureDeviceId);
                Robot robot = captureAllScreens ? new Robot() : new Robot(device);
                Rectangle captureArea = captureAllScreens ? virtualDesktopBounds() : device.getDefaultConfiguration().getBounds();
                output = new DataOutputStream(socket.getOutputStream());
                Thread commands = new Thread(() -> readCommands(robot, captureArea), "proyector-commands");
                commands.setDaemon(true);
                commands.start();
                Quality quality = Quality.values()[Math.max(0, Math.min(qualityIndex, Quality.values().length - 1))];
                while (!socket.isClosed()) {
                    long started = System.currentTimeMillis();
                    BufferedImage screenshot = robot.createScreenCapture(captureArea);
                    BufferedImage scaled = scale(screenshot, quality.maxWidth, quality.maxHeight);
                    byte[] jpeg = encodeJpeg(scaled, quality.jpegQuality);
                    synchronized (output) {
                        output.writeInt(jpeg.length);
                        output.write(jpeg);
                        output.flush();
                    }
                    int frameDelay = targetFps > 0 ? Math.max(1, 1000 / targetFps) : quality.frameDelayMs;
                    long remaining = frameDelay - (System.currentTimeMillis() - started);
                    if (remaining > 0) Thread.sleep(remaining);
                }
            } catch (Exception error) {
                status.set("Transmisión finalizada: " + error.getMessage());
            }
        }

        private void readCommands(Robot robot, Rectangle captureArea) {
            try {
                while (!socket.isClosed()) {
                    int command = input.readInt();
                    if (command == 15) {
                        WindowsAction.queryVolume(this::sendNotice);
                    } else if (command == 16) {
                        int percent = input.readInt();
                        if (percent < 0 || percent > 100) throw new IOException("Nivel de volumen inválido");
                        WindowsAction.setVolume(percent, this::sendNotice);
                    } else if (command >= 1 && command <= 4 || command >= 10 && command <= 12) {
                        sendNotice(true, "Orden recibida por Windows. Ejecutando…");
                        actionHandler.accept(command, this::sendNotice);
                    } else if (command == 5 || command == 6 || command == 7) {
                        int x = input.readInt();
                        int y = input.readInt();
                        if (x == -1 && y == -1) { /* usar la posición actual del panel táctil */ }
                        else {
                            if (x < 0 || x > 1000000 || y < 0 || y > 1000000) throw new IOException("Coordenadas inválidas");
                            robot.mouseMove(captureArea.x + (int) Math.round((captureArea.width - 1) * x / 1000000.0),
                                    captureArea.y + (int) Math.round((captureArea.height - 1) * y / 1000000.0));
                        }
                        if (command == 6) robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                        if (command == 7) robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    } else if (command == 8) {
                        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
                        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
                    } else if (command == 9) {
                        int ticks = input.readInt();
                        if (ticks < -12 || ticks > 12) throw new IOException("Desplazamiento inválido");
                        robot.mouseWheel(ticks);
                    } else if (command == 13) {
                        int dx = input.readInt();
                        int dy = input.readInt();
                        if (Math.abs(dx) > 1000 || Math.abs(dy) > 1000) throw new IOException("Movimiento inválido");
                        Point pointer = MouseInfo.getPointerInfo().getLocation();
                        robot.mouseMove(Math.max(captureArea.x, Math.min(captureArea.x + captureArea.width - 1, pointer.x + dx)),
                                Math.max(captureArea.y, Math.min(captureArea.y + captureArea.height - 1, pointer.y + dy)));
                    } else if (command == 14) {
                        String value = input.readUTF();
                        if (value.length() > 512) throw new IOException("Texto demasiado largo");
                        if (!value.isEmpty()) pasteText(robot, value);
                    } else {
                        throw new IOException("Control desconocido");
                    }
                }
            } catch (Exception error) {
                try { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); } catch (Exception ignored) { }
                try { socket.close(); } catch (IOException ignored) { }
            }
        }

        private void sendNotice(boolean success, String message) {
            DataOutputStream stream = output;
            if (stream == null || socket.isClosed()) return;
            try {
                synchronized (stream) {
                    stream.writeInt(-2);
                    stream.writeBoolean(success);
                    stream.writeUTF(message);
                    stream.flush();
                }
            } catch (IOException error) {
                status.set("No se pudo informar el resultado al teléfono: " + error.getMessage());
            }
        }

        private static void pasteText(Robot robot, String value) {
            java.awt.datatransfer.Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable previous = clipboard.getContents(null);
            try {
                clipboard.setContents(new StringSelection(value), null);
                robot.keyPress(KeyEvent.VK_CONTROL);
                robot.keyPress(KeyEvent.VK_V);
                robot.keyRelease(KeyEvent.VK_V);
                robot.keyRelease(KeyEvent.VK_CONTROL);
                robot.delay(150);
            } finally {
                if (previous != null) clipboard.setContents(previous, null);
            }
        }

        private static BufferedImage scale(BufferedImage source, int maxWidth, int maxHeight) {
            double factor = Math.min(1.0, Math.min(maxWidth / (double) source.getWidth(),
                    maxHeight / (double) source.getHeight()));
            if (factor >= 1.0) return source;
            int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
            int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = result.createGraphics();
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
            graphics.dispose();
            return result;
        }

        private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(output);
                ImageWriteParam params = writer.getDefaultWriteParam();
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
                writer.write(null, new IIOImage(image, null, null), params);
            } finally {
                writer.dispose();
            }
            return bytes.toByteArray();
        }

        private static Rectangle virtualDesktopBounds() {
            Rectangle result = new Rectangle();
            for (java.awt.GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                result = result.union(device.getDefaultConfiguration().getBounds());
            }
            if (result.width == 0 || result.height == 0) {
                java.awt.Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
                return new Rectangle(0, 0, size.width, size.height);
            }
            return result;
        }

        private static String primaryDeviceId() {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getIDstring();
        }

        private static GraphicsDevice findDevice(String id) {
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                if (device.getIDstring().equals(id)) return device;
            }
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        }
    }

    private static final class WindowsAction {
        static final int SUSPEND = 1;
        static final int SHUTDOWN = 2;
        static final int VOLUME_MUTE = 3;
        static final int VOLUME_UP = 4;
        static final int LOCK = 10;
        static final int RESTART = 11;
        static final int VOLUME_DOWN = 12;

        static void execute(int command, StatusListener status, BiConsumer<Boolean, String> result) {
            if (command == VOLUME_MUTE || command == VOLUME_UP || command == VOLUME_DOWN) {
                audioAction(command == VOLUME_MUTE ? "mute" : command == VOLUME_UP ? "up" : "down", result);
                return;
            }
            final List<String> arguments;
            final String description;
            switch (command) {
                case SUSPEND:
                    arguments = java.util.Arrays.asList("rundll32.exe", "powrprof.dll,SetSuspendState", "0", "1", "0");
                    description = "La PC entró en suspensión o está despertando.";
                    break;
                case SHUTDOWN:
                    arguments = java.util.Arrays.asList("shutdown.exe", "/s", "/t", "10", "/c", "Apagado solicitado desde Proyector");
                    description = "Windows se apagará en 10 segundos.";
                    break;
                case LOCK:
                    arguments = java.util.Arrays.asList("rundll32.exe", "user32.dll,LockWorkStation");
                    description = "Sesión bloqueada.";
                    break;
                case RESTART:
                    arguments = java.util.Arrays.asList("shutdown.exe", "/r", "/t", "10", "/c", "Reinicio solicitado desde Proyector");
                    description = "Windows se reiniciará en 10 segundos.";
                    break;
                default:
                    status.set("Control desconocido recibido.");
                    result.accept(false, "Windows recibió un control desconocido.");
                    return;
            }
            Thread action = new Thread(() -> {
                try {
                    Process process = new ProcessBuilder(arguments)
                            .redirectErrorStream(true).start();
                    if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        report(status, result, false, "La orden tardó demasiado. Comprueba Windows.");
                    } else if (process.exitValue() == 0) report(status, result, true, description);
                    else {
                        String detail = readProcessError(process);
                        report(status, result, false, "Windows rechazó la orden (" + process.exitValue() + ")"
                                + (detail.isEmpty() ? "." : ": " + detail));
                    }
                } catch (Exception error) {
                    report(status, result, false, "No se pudo ejecutar la orden: " + error.getMessage());
                }
            }, "proyector-windows-action");
            action.setDaemon(true);
            action.start();
        }

        private static String readProcessError(Process process) throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) return line.length() > 160 ? line.substring(0, 160) : line;
                }
            }
            return "";
        }

        static void queryVolume(BiConsumer<Boolean, String> result) {
            audioAction("status", result);
        }

        static void setVolume(int percent, BiConsumer<Boolean, String> result) {
            audioAction("set:" + percent, result);
        }

        private static void audioAction(String action, BiConsumer<Boolean, String> result) {
            Thread worker = new Thread(() -> {
                try {
                    String encoded = Base64.getEncoder().encodeToString(audioScript(action).getBytes(StandardCharsets.UTF_16LE));
                    Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                            "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                            .redirectErrorStream(true).start();
                    if (!process.waitFor(7, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        result.accept(false, "Windows tardó demasiado en responder al control de audio.");
                        return;
                    }
                    String response;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder text = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (text.length() > 0) text.append(' ');
                            text.append(line.trim());
                        }
                        response = text.toString().replace("\u0000", "").trim();
                    }
                    if (process.exitValue() != 0) {
                        result.accept(false, "Windows no pudo leer el volumen. Comprueba que haya un dispositivo de audio activo.");
                        return;
                    }
                    if ("status".equals(action)) {
                        if (!response.startsWith("STAT:")) throw new IOException("Estado de Windows no válido");
                        result.accept(true, response);
                    } else {
                        String[] parts = response.split("\\|");
                        int percent = Integer.parseInt(parts[0]);
                        boolean muted = parts.length > 1 && "1".equals(parts[1]);
                        result.accept(true, "VOL:" + percent + ":" + (muted ? "1" : "0"));
                    }
                } catch (Exception error) {
                    result.accept(false, "No se pudo controlar el volumen de Windows. Revisa el dispositivo de audio.");
                }
            }, "proyector-audio-control");
            worker.setDaemon(true);
            worker.start();
        }

        private static String audioScript(String action) {
            String csharp = String.join("\n",
                    "using System;",
                    "using System.Globalization;",
                    "using System.Runtime.InteropServices;",
                    "[ComImport, Guid(\"BCDE0395-E52F-467C-8E3D-C4579291692E\")] public class MMDeviceEnumeratorComObject {}",
                    "[ComImport, Guid(\"A95664D2-9614-4F35-A746-DE8DB63617E6\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
                    "public interface IMMDeviceEnumerator {",
                    "[PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);",
                    "[PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);",
                    "[PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);",
                    "[PreserveSig] int RegisterEndpointNotificationCallback(IntPtr callback);",
                    "[PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr callback);",
                    "}",
                    "[ComImport, Guid(\"D666063F-1587-4E43-81F1-B948E807363F\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
                    "public interface IMMDevice {",
                    "[PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object result);",
                    "[PreserveSig] int OpenPropertyStore(int access, out IntPtr properties);",
                    "[PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);",
                    "[PreserveSig] int GetState(out int state);",
                    "}",
                    "[ComImport, Guid(\"5CDF2C82-841E-4546-9722-0CF74078229A\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
                    "public interface IAudioEndpointVolume {",
                    "[PreserveSig] int RegisterControlChangeNotify(IntPtr callback);",
                    "[PreserveSig] int UnregisterControlChangeNotify(IntPtr callback);",
                    "[PreserveSig] int GetChannelCount(out uint count);",
                    "[PreserveSig] int SetMasterVolumeLevel(float level, ref Guid context);",
                    "[PreserveSig] int SetMasterVolumeLevelScalar(float level, ref Guid context);",
                    "[PreserveSig] int GetMasterVolumeLevel(out float level);",
                    "[PreserveSig] int GetMasterVolumeLevelScalar(out float level);",
                    "[PreserveSig] int SetChannelVolumeLevel(uint channel, float level, ref Guid context);",
                    "[PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid context);",
                    "[PreserveSig] int GetChannelVolumeLevel(uint channel, out float level);",
                    "[PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);",
                    "[PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool muted, ref Guid context);",
                    "[PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool muted);",
                    "}",
                    "public static class CoreAudioBridge {",
                    "public static string Run(string action) {",
                    "var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();",
                    "IMMDevice endpoint; Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out endpoint));",
                    "Guid iid = typeof(IAudioEndpointVolume).GUID; object raw; Marshal.ThrowExceptionForHR(endpoint.Activate(ref iid, 23, IntPtr.Zero, out raw));",
                    "var volume = (IAudioEndpointVolume)raw; float scalar; bool muted;",
                    "Marshal.ThrowExceptionForHR(volume.GetMasterVolumeLevelScalar(out scalar)); Marshal.ThrowExceptionForHR(volume.GetMute(out muted));",
                    "Guid context = Guid.Empty;",
                    "if (action == \"up\") Marshal.ThrowExceptionForHR(volume.SetMasterVolumeLevelScalar(Math.Min(1f, scalar + 0.05f), ref context));",
                    "else if (action == \"down\") Marshal.ThrowExceptionForHR(volume.SetMasterVolumeLevelScalar(Math.Max(0f, scalar - 0.05f), ref context));",
                    "else if (action == \"mute\") Marshal.ThrowExceptionForHR(volume.SetMute(!muted, ref context));",
                    "else if (action.StartsWith(\"set:\")) { int level; if (!Int32.TryParse(action.Substring(4), out level) || level < 0 || level > 100) throw new ArgumentOutOfRangeException(\"level\"); Marshal.ThrowExceptionForHR(volume.SetMasterVolumeLevelScalar(level / 100f, ref context)); }",
                    "Marshal.ThrowExceptionForHR(volume.GetMasterVolumeLevelScalar(out scalar)); Marshal.ThrowExceptionForHR(volume.GetMute(out muted));",
                    "return ((int)Math.Round(scalar * 100)).ToString(CultureInfo.InvariantCulture) + \"|\" + (muted ? \"1\" : \"0\");",
                    "}",
                    "}");
            String audioCommand = "status".equals(action) ? "get" : action;
            String script = "$ErrorActionPreference = 'Stop'\n"
                    + "$source = @'\n" + csharp + "\n'@\n"
                    + "try { Add-Type -TypeDefinition $source -ErrorAction Stop; $audio = [CoreAudioBridge]::Run('" + audioCommand + "'); ";
            if ("status".equals(action)) {
                script += "$parts = $audio.Split('|'); $battery = Get-CimInstance -ClassName Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1; "
                        + "$batteryPercent = 'NA'; $charging = 0; if ($null -ne $battery) { if ($null -ne $battery.EstimatedChargeRemaining) { $batteryPercent = [string][int]$battery.EstimatedChargeRemaining }; "
                        + "$state = [int]$battery.BatteryStatus; if ($state -eq 6 -or $state -eq 7 -or $state -eq 8 -or $state -eq 9) { $charging = 1 } }; "
                        + "[Console]::WriteLine('STAT:' + $parts[0] + ':' + $parts[1] + ':' + $batteryPercent + ':' + $charging) } ";
            } else {
                script += "[Console]::WriteLine($audio) } ";
            }
            return script + "catch { [Console]::Error.WriteLine($_.Exception.Message); exit 3 }";
        }

        private static void report(StatusListener status, BiConsumer<Boolean, String> result, boolean success, String message) {
            status.set(message);
            result.accept(success, message);
        }

        private static String sendKeysScript(int[] keys) {
            String typeName = "ProjectorKeys" + Long.toHexString(System.nanoTime());
            String csharp = "using System;\nusing System.Runtime.InteropServices;\npublic static class "
                    + typeName + " { [DllImport(\"user32.dll\")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo); }";
            StringBuilder source = new StringBuilder();
            source.append("$source = @'\n").append(csharp).append("\n'@; Add-Type -TypeDefinition $source; ");
            for (int key : keys) {
                source.append("[").append(typeName).append("]::keybd_event(").append(key).append(",0,0,[UIntPtr]::Zero); ")
                        .append("Start-Sleep -Milliseconds 100; [").append(typeName).append("]::keybd_event(").append(key).append(",0,2,[UIntPtr]::Zero); ");
            }
            return source.toString();
        }
    }

    private enum Quality {
        LOW(640, 360, 0.36f, 120), BALANCED(960, 540, 0.52f, 83),
        HIGH(1280, 720, 0.68f, 66), FULL_HD(1920, 1080, 0.76f, 100);
        final int maxWidth;
        final int maxHeight;
        final float jpegQuality;
        final int frameDelayMs;
        Quality(int maxWidth, int maxHeight, float jpegQuality, int frameDelayMs) {
            this.maxWidth = maxWidth; this.maxHeight = maxHeight;
            this.jpegQuality = jpegQuality; this.frameDelayMs = frameDelayMs;
        }
    }

    private static final class AddressInfo {
        final String name;
        final String address;
        AddressInfo(String name, String address) { this.name = name; this.address = address; }
        @Override public String toString() { return name + "=" + address; }
    }

    private static final class AuthAttempts {
        int failures;
        long blockedUntil;
    }

    private static final class RoundedPanel extends JPanel {
        private final Color background;
        private final int radius;
        RoundedPanel(Color background, int radius) { this.background = background; this.radius = radius; setOpaque(false); }
        @Override protected void paintComponent(java.awt.Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setPaint(new java.awt.GradientPaint(0, 0, background.brighter(), getWidth(), getHeight(), background.darker()));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class SmoothButton extends JButton {
        private Color current;
        private Color base;
        private Color hover;
        private boolean hovering;
        private Timer animation;

        SmoothButton(String text, Color color) {
            super(text);
            setContentAreaFilled(false);
            setOpaque(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder(13, 22, 13, 22));
            setBackground(color);
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent event) { animateTo(true); }
                @Override public void mouseExited(java.awt.event.MouseEvent event) { animateTo(false); }
                @Override public void mousePressed(java.awt.event.MouseEvent event) { animateTo(true); }
                @Override public void mouseReleased(java.awt.event.MouseEvent event) { animateTo(contains(event.getPoint())); }
            });
        }

        @Override public void setBackground(Color color) {
            if (color == null) return;
            base = color;
            hover = mix(color, Color.WHITE, 0.14f);
            if (current == null || !hovering) current = color;
            repaint();
        }

        private void animateTo(boolean targetHover) {
            hovering = targetHover;
            Color target = targetHover ? hover : base;
            if (animation != null) animation.stop();
            animation = new Timer(16, event -> {
                current = approach(current, target, 0.22f);
                repaint();
                if (colorDistance(current, target) < 2) {
                    current = target;
                    animation.stop();
                }
            });
            animation.start();
        }

        @Override protected void paintComponent(java.awt.Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(current == null ? base : current);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g.dispose();
            super.paintComponent(graphics);
        }

        private static Color mix(Color first, Color second, float amount) {
            return new Color(
                    Math.min(255, Math.round(first.getRed() + (second.getRed() - first.getRed()) * amount)),
                    Math.min(255, Math.round(first.getGreen() + (second.getGreen() - first.getGreen()) * amount)),
                    Math.min(255, Math.round(first.getBlue() + (second.getBlue() - first.getBlue()) * amount)));
        }

        private static Color approach(Color current, Color target, float amount) {
            return mix(current == null ? target : current, target, amount);
        }

        private static int colorDistance(Color first, Color second) {
            return Math.abs(first.getRed() - second.getRed())
                    + Math.abs(first.getGreen() - second.getGreen())
                    + Math.abs(first.getBlue() - second.getBlue());
        }
    }

    private static final class BoxGap {
        static JPanel vertical(int size) { JPanel panel = new JPanel(); panel.setOpaque(false); panel.setPreferredSize(new Dimension(1, size)); panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, size)); panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); return panel; }
        static JPanel horizontal(int size) { JPanel panel = new JPanel(); panel.setOpaque(false); panel.setPreferredSize(new Dimension(size, 1)); return panel; }
    }

    private static void styleField(JTextField field) {
        field.setBackground(new Color(9, 26, 54));
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(56, 92, 143)), BorderFactory.createEmptyBorder(7, 9, 7, 9)));
    }

    private static void styleCombo(JComboBox<String> combo) {
        combo.setBackground(new Color(25, 51, 93));
        combo.setForeground(Color.WHITE);
        combo.setFocusable(false);
    }

    private static void styleSmallButton(JButton button) {
        button.setFocusPainted(false);
        button.setForeground(Color.WHITE);
        button.setBackground(new Color(59, 62, 70));
        button.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
    }

    private static String newPairingPin() {
        int value = new SecureRandom().nextInt(1000000);
        return String.format(java.util.Locale.ROOT, "%06d", value);
    }

    private static boolean samePin(String expected, String provided) {
        if (expected == null || provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static List<AddressInfo> localAddresses() {
        List<AddressInfo> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        result.add(new AddressInfo(shortInterfaceName(network.getDisplayName()), address.getHostAddress()));
                    }
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(result, (left, right) -> left.name.compareToIgnoreCase(right.name));
        return result;
    }

    private static String shortInterfaceName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("tailscale")) return "Tailscale · VPN";
        if (lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("wireless")) return "Wi‑Fi";
        if (lower.contains("ethernet")) return "Ethernet";
        if (lower.contains("remote ndis") || lower.contains("usb")) return "USB";
        return name.length() > 30 ? name.substring(0, 27) + "…" : name;
    }

    private static boolean isAllowedRemoteAddress(InetAddress address) {
        if (address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()) return true;
        if (!(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress();
        return (bytes[0] & 0xff) == 100 && ((bytes[1] & 0xff) & 0xc0) == 0x40;
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast != null && broadcast instanceof Inet4Address && !result.contains(broadcast)) result.add(broadcast);
                }
            }
        } catch (Exception ignored) { }
        return result;
    }

    private interface StatusListener { void set(String message); }
}
