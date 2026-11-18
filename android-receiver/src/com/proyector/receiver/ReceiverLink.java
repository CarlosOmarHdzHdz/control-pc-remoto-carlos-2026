package com.proyector.receiver;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.SystemClock;

import com.proyector.shared.Wire;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class ReceiverLink implements AutoCloseable {
    enum Mode { LAN, USB, VPN }
    enum State { IDLE, CONNECTING, VERIFYING, CONNECTED, RETRYING }

    interface Listener {
        void state(State state, String message, boolean error);
        void nearby(List<Endpoint> devices);
        void verify(String code, Approval approval);
        void linked(String computer, String fingerprint, Endpoint endpoint, Mode mode);
        void screens(int count, String secondary);
        void vpnRoute(String address, int port);
        void result(boolean ok, String message);
        void latency(long millis);
        void frame(Bitmap image, int revision, int sequence);
    }

    static final class Endpoint {
        final InetAddress ip;
        final int port;
        final String fingerprint;
        final String computer;
        final Mode mode;
        final long observed;

        Endpoint(InetAddress ip, int port, String fingerprint, String computer, Mode mode, long observed) {
            this.ip = ip;
            this.port = port;
            this.fingerprint = fingerprint;
            this.computer = computer;
            this.mode = mode;
            this.observed = observed;
        }

        String address() { return ip.getHostAddress() + ":" + port; }
    }

    static final class Approval {
        private final CountDownLatch decision = new CountDownLatch(1);
        private volatile boolean accepted;

        synchronized void answer(boolean yes) {
            if (decision.getCount() == 0) return;
            accepted = yes;
            decision.countDown();
        }

        boolean pending() { return decision.getCount() != 0; }
        boolean waitForAnswer() throws InterruptedException { return decision.await(75, TimeUnit.SECONDS) && accepted; }
    }

    private final Handler main;
    private final Listener ui;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
            task -> thread(task, "pc-heartbeat"));
    private final Map<String, Endpoint> offers = new LinkedHashMap<>();
    private final Thread dialer;
    private final Thread discoverer;
    private volatile boolean closed;
    private volatile boolean foreground = true;
    private volatile Target target;
    private volatile Socket working;
    private volatile Connection connection;
    private volatile DatagramSocket broadcast;
    private volatile Approval pendingApproval;
    private volatile View view = new View(1, Wire.PAUSED, 2, 30, true);
    private int ticks;

    ReceiverLink(Handler handler, Listener listener) {
        main = handler;
        ui = listener;
        dialer = thread(this::dial, "pc-dial");
        discoverer = thread(this::listenForBroadcasts, "pc-discovery");
        dialer.start();
        discoverer.start();
        ticker.scheduleWithFixedDelay(this::tick, 2, 3, TimeUnit.SECONDS);
    }

    synchronized void connect(Mode mode, Endpoint endpoint, boolean automatic, String pin, String fingerprint) {
        cancelCurrent();
        target = new Target(mode, endpoint, automatic, pin, fingerprint);
        notifyAll();
        ui.state(State.CONNECTING, "Abriendo conexión con Windows…", false);
    }

    synchronized void disconnect() {
        cancelCurrent();
        target = null;
        notifyAll();
        ui.state(State.IDLE, "Sin conexión", false);
    }

    private void cancelCurrent() {
        Approval approval = pendingApproval;
        if (approval != null) approval.answer(false);
        Socket socket = working;
        if (socket != null) thread(() -> close(socket), "pc-cancel").start();
        connection = null;
    }

    synchronized int show(int mode, int quality, int fps, boolean automatic) {
        View next = new View(view.revision + 1, mode, quality, fps, automatic);
        view = next;
        Connection link = connection;
        if (link != null) link.sendView(next);
        return next.revision;
    }

    void foreground(boolean value) { foreground = value; }

    boolean send(int command, int... args) {
        Connection link = connection;
        if (link == null) return false;
        return link.enqueue(output -> {
            output.writeInt(command);
            for (int value : args) output.writeInt(value);
        }, false);
    }

    void pointer(int command, int sequence, int... args) {
        Connection link = connection;
        if (link == null) return;
        int revision = view.revision;
        link.enqueue(output -> {
            output.writeInt(command);
            output.writeInt(revision);
            output.writeInt(sequence);
            for (int value : args) output.writeInt(value);
        }, command == Wire.MOVE || command == Wire.RELATIVE || command == Wire.SCROLL);
    }

    boolean text(String text, int sequence) {
        Connection link = connection;
        if (link == null || text.length() > 512) return false;
        int revision = view.revision;
        return link.enqueue(output -> {
            output.writeInt(Wire.TEXT);
            output.writeInt(revision);
            output.writeInt(sequence);
            output.writeUTF(text);
        }, false);
    }

    private void tick() {
        Connection link = connection;
        if (link == null) return;
        link.enqueue(output -> { output.writeInt(Wire.PING); output.writeLong(SystemClock.elapsedRealtime()); }, false);
        if (foreground && ++ticks % 2 == 0) send(Wire.STATUS);
    }

    private void dial() {
        Target attempt = null;
        int failures = 0;
        while (!closed) {
            Target desired;
            synchronized (this) {
                while (!closed && target == null) {
                    try { wait(); } catch (InterruptedException stop) { return; }
                }
                if (closed) return;
                desired = target;
            }
            if (desired != attempt) { attempt = desired; failures = 0; }
            Endpoint endpoint = endpointFor(desired);
            if (endpoint == null) {
                post(desired, () -> ui.state(State.RETRYING, "Buscando tu computadora…", false));
                waitBeforeRetry(desired, 1000);
                continue;
            }
            long started = SystemClock.elapsedRealtime();
            try {
                join(desired, endpoint);
            } catch (PairingFailure error) {
                stopAttempt(desired, error.getMessage());
                continue;
            } catch (IOException | GeneralSecurityException error) {
                if (target != desired) continue;
                if (desired.token.isEmpty() && failures >= 1) {
                    String message = error instanceof SSLException
                            ? "No se pudo verificar el cifrado. Actualiza el emisor y el receptor a la misma versión."
                            : "No se pudo llegar a esa PC. Comprueba el emisor, la red y el puerto.";
                    stopAttempt(desired, message);
                    continue;
                }
                post(desired, () -> ui.state(State.RETRYING, "Conexión interrumpida. Reconectando…", false));
            }
            if (SystemClock.elapsedRealtime() - started > 12_000) failures = 0;
            waitBeforeRetry(desired, Math.min(6500, 650L << Math.min(failures++, 4)));
        }
    }

    private void join(Target desired, Endpoint endpoint) throws IOException, GeneralSecurityException, PairingFailure {
        Socket raw = new Socket();
        synchronized (this) {
            if (target != desired || closed) { close(raw); return; }
            working = raw;
        }
        Connection next = null;
        try (Socket base = raw) {
            base.setTcpNoDelay(true);
            base.setKeepAlive(true);
            base.connect(new InetSocketAddress(endpoint.ip, endpoint.port), 2300);
            base.setSoTimeout(7000);
            String expected = desired.fingerprint;
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{new LocalTrust(expected)}, null);
            try (SSLSocket encrypted = (SSLSocket) tls.getSocketFactory()
                    .createSocket(base, endpoint.ip.getHostAddress(), endpoint.port, true)) {
                List<String> protocols = new ArrayList<>();
                for (String protocol : encrypted.getSupportedProtocols())
                    if ("TLSv1.3".equals(protocol) || "TLSv1.2".equals(protocol)) protocols.add(protocol);
                encrypted.setEnabledProtocols(protocols.toArray(new String[0]));
                try { encrypted.startHandshake(); }
                catch (SSLException error) {
                    if (error.getCause() instanceof ReplacedIdentity)
                        throw new PairingFailure("La identidad de esa PC cambió. Revisa el equipo y usa Olvidar PC para volver a vincularlo.");
                    throw error;
                }
                String fingerprint = Wire.fingerprint(encrypted.getSession().getPeerCertificates()[0]);
                if (expected != null && !expected.equals(fingerprint))
                    throw new PairingFailure("La identidad de esa PC cambió. Revisa el equipo.");
                if (expected == null) {
                    if (endpoint.fingerprint != null && !fingerprint.equals(endpoint.fingerprint))
                        throw new PairingFailure("La identidad anunciada no coincide. Introduce la IP que muestra Windows.");
                    verify(desired, fingerprint);
                }

                if (target != desired || closed) return;
                encrypted.setSoTimeout(12_000);
                DataInputStream input = new DataInputStream(new BufferedInputStream(encrypted.getInputStream(), 32 * 1024));
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(encrypted.getOutputStream(), 8192));
                output.writeInt(Wire.MAGIC);
                output.writeUTF(desired.pin);
                output.writeUTF(desired.token);
                output.flush();
                switch (input.readInt()) {
                    case Wire.OK: break;
                    case Wire.BAD_PIN: throw new PairingFailure("Ese código ya no es válido. Escribe el que muestra el emisor.");
                    case Wire.TRY_LATER: throw new PairingFailure("Demasiados intentos. Espera un minuto e inténtalo de nuevo.");
                    case Wire.IN_USE: throw new PairingFailure("Esa PC está conectada a otro receptor. Desconéctalo y vuelve a intentar.");
                    default: throw new PairingFailure("Las versiones de Windows y Android no coinciden.");
                }
                String computer = Wire.displayName(input.readUTF());
                String token = input.readUTF();
                if (!token.matches("[a-f0-9]{48}")) throw new IOException("Sesión inválida");
                next = new Connection(base, encrypted, output);
                synchronized (this) {
                    if (target != desired || closed) return;
                    desired.token = token;
                    desired.fingerprint = fingerprint;
                    desired.last = endpoint;
                    connection = next;
                    next.start();
                    next.sendView(view);
                    if (token.startsWith(Wire.ROUTE_TOKEN_PREFIX)) {
                        next.enqueue(out -> out.writeInt(Wire.REQUEST_VPN_ROUTE), false);
                    }
                }
                Connection linked = next;
                current(desired, linked, () -> {
                    ui.linked(computer, fingerprint, endpoint, desired.mode);
                    ui.state(State.CONNECTED, "Conexión cifrada", false);
                });
                receive(desired, linked, input);
            }
        } finally {
            if (next != null) next.stop();
            synchronized (this) {
                if (working == raw) working = null;
                if (connection == next) connection = null;
            }
        }
    }

    private void receive(Target desired, Connection link, DataInputStream input) throws IOException {
        byte[] bytes = new byte[96 * 1024];
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        while (target == desired && !closed && !link.socket.isClosed()) {
            switch (input.readInt()) {
                case Wire.FRAME: {
                    int revision = input.readInt();
                    int sequence = input.readInt();
                    int length = input.readInt();
                    if (length <= 0 || length > Wire.MAX_FRAME) throw new IOException("Imagen inválida");
                    if (bytes.length < length) bytes = new byte[length];
                    input.readFully(bytes, 0, length);
                    if (foreground && revision == view.revision && view.mode != Wire.PAUSED) {
                        BitmapFactory.decodeByteArray(bytes, 0, length, bounds);
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 1920
                                || bounds.outHeight > 1920 || bounds.outWidth * bounds.outHeight > 1920 * 1080)
                            throw new IOException("Resolución inválida");
                        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, length, options);
                        if (bitmap == null) throw new IOException("No se pudo leer la imagen");
                        if (link == connection && revision == view.revision) ui.frame(bitmap, revision, sequence);
                        else bitmap.recycle();
                    }
                    link.enqueue(output -> { output.writeInt(Wire.FRAME_ACK); output.writeInt(sequence); }, false);
                    break;
                }
                case Wire.PONG: {
                    long delay = SystemClock.elapsedRealtime() - input.readLong();
                    if (delay >= 0 && delay <= 12_000) current(desired, link, () -> ui.latency(delay));
                    break;
                }
                case Wire.DISPLAYS: {
                    int count = input.readInt();
                    String secondary = input.readUTF();
                    if (count < 1 || count > 64 || secondary.length() > 128) throw new IOException("Pantallas inválidas");
                    current(desired, link, () -> ui.screens(count, secondary));
                    break;
                }
                case Wire.VPN_ROUTE: {
                    String address = input.readUTF();
                    int port = input.readInt();
                    if (port < 1024 || port > 65535 ||
                            (!address.isEmpty() && (address.length() > 15 || !Wire.isVpnAddress(Wire.parseAddress(address))))) {
                        throw new IOException("Ruta privada inválida");
                    }
                    current(desired, link, () -> ui.vpnRoute(address, port));
                    break;
                }
                case Wire.NOTICE: {
                    boolean ok = input.readBoolean();
                    String message = input.readUTF();
                    if (message.length() > 512) throw new IOException("Mensaje inválido");
                    current(desired, link, () -> ui.result(ok, message));
                    break;
                }
                default: throw new IOException("Respuesta desconocida");
            }
        }
    }

    private void verify(Target desired, String fingerprint) throws PairingFailure {
        Approval approval = new Approval();
        synchronized (this) {
            if (target != desired || closed) throw new PairingFailure("Vinculación cancelada.");
            pendingApproval = approval;
        }
        try {
            post(desired, () -> {
                ui.state(State.VERIFYING, "Compara la identidad con Windows", false);
                ui.verify(Wire.identityCode(fingerprint), approval);
            });
            if (!approval.waitForAnswer()) throw new PairingFailure("La vinculación se canceló. Intenta de nuevo cuando tengas la PC a la vista.");
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            throw new PairingFailure("Vinculación cancelada.");
        } finally {
            approval.answer(false);
            pendingApproval = null;
        }
    }

    private Endpoint endpointFor(Target desired) {
        if (!desired.automatic) return desired.last;
        String fingerprint = desired.fingerprint;
        if (fingerprint == null && desired.last != null) fingerprint = desired.last.fingerprint;
        Endpoint matching = null;
        long now = SystemClock.elapsedRealtime();
        synchronized (offers) {
            for (Endpoint offer : offers.values()) {
                if (offer.mode == desired.mode && offer.fingerprint.equals(fingerprint) && now - offer.observed < 4500
                        && (matching == null || matching.observed < offer.observed)) matching = offer;
            }
        }
        return matching == null ? desired.last : matching;
    }

    private void listenForBroadcasts() {
        while (!closed) {
            try (DatagramSocket receive = new DatagramSocket(null)) {
                broadcast = receive;
                receive.setReuseAddress(true);
                receive.bind(new InetSocketAddress(Wire.DISCOVERY_PORT));
                receive.setSoTimeout(900);
                byte[] data = new byte[320];
                DatagramPacket packet = new DatagramPacket(data, data.length);
                while (!closed) {
                    boolean changed = false;
                    try {
                        packet.setLength(data.length);
                        receive.receive(packet);
                        Endpoint offer = parse(packet);
                        if (offer != null) {
                            String key = offer.mode + ":" + offer.fingerprint + ":" + offer.address();
                            synchronized (offers) {
                                if (offers.size() < 32 || offers.containsKey(key)) {
                                    Endpoint old = offers.put(key, offer);
                                    changed = old == null || !old.computer.equals(offer.computer);
                                }
                            }
                        }
                    } catch (SocketTimeoutException idle) { }
                    List<Endpoint> snapshot = null;
                    synchronized (offers) {
                        Iterator<Endpoint> iterator = offers.values().iterator();
                        while (iterator.hasNext()) {
                            if (SystemClock.elapsedRealtime() - iterator.next().observed >= 5000) {
                                iterator.remove();
                                changed = true;
                            }
                        }
                        if (changed) snapshot = new ArrayList<>(offers.values());
                    }
                    if (snapshot != null) {
                        List<Endpoint> latest = Collections.unmodifiableList(snapshot);
                        main.post(() -> { if (!closed) ui.nearby(latest); });
                        synchronized (this) { notifyAll(); }
                    }
                }
            } catch (IOException error) {
                if (!closed) SystemClock.sleep(1600);
            } finally { broadcast = null; }
        }
    }

    private static Endpoint parse(DatagramPacket packet) {
        if (!Wire.isLocalAddress(packet.getAddress()) || packet.getAddress().isLoopbackAddress()) return null;
        String content = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        String[] parts = content.split("\\|", 5);
        if (parts.length != 5 || !Wire.DISCOVERY_PREFIX.equals(parts[0]) || !parts[2].matches("[a-f0-9]{64}")) return null;
        Mode mode;
        if ("USB".equals(parts[3])) mode = Mode.USB;
        else if ("LAN".equals(parts[3])) mode = Mode.LAN;
        else return null;
        try {
            int port = Integer.parseInt(parts[1]);
            if (port < 1024 || port > 65535) return null;
            return new Endpoint(packet.getAddress(), port, parts[2], Wire.displayName(parts[4]), mode, SystemClock.elapsedRealtime());
        } catch (NumberFormatException invalid) { return null; }
    }

    private void waitBeforeRetry(Target desired, long millis) {
        synchronized (this) {
            if (target == desired && !closed) {
                try { wait(millis); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void stopAttempt(Target expected, String message) {
        main.post(() -> {
            synchronized (this) {
                if (target != expected || closed) return;
                target = null;
                notifyAll();
            }
            ui.state(State.IDLE, message, true);
        });
        synchronized (this) {
            while (target == expected && !closed) {
                try { wait(); } catch (InterruptedException stop) { return; }
            }
        }
    }

    private void post(Target expected, Runnable action) {
        main.post(() -> { if (target == expected && !closed) action.run(); });
    }

    private void current(Target expected, Connection link, Runnable action) {
        main.post(() -> { if (target == expected && connection == link && !closed) action.run(); });
    }

    @Override public synchronized void close() {
        closed = true;
        cancelCurrent();
        target = null;
        notifyAll();
        DatagramSocket receiver = broadcast;
        if (receiver != null) receiver.close();
        ticker.shutdownNow();
        discoverer.interrupt();
        dialer.interrupt();
    }

    private static void close(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }

    private static Thread thread(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class Target {
        final Mode mode;
        Endpoint last;
        final boolean automatic;
        final String pin;
        String fingerprint;
        String token = "";

        Target(Mode mode, Endpoint endpoint, boolean automatic, String pin, String fingerprint) {
            this.mode = mode;
            last = endpoint;
            this.automatic = automatic;
            this.pin = pin;
            this.fingerprint = fingerprint;
        }
    }

    private static final class View {
        final int revision, mode, quality, fps;
        final boolean automatic;

        View(int revision, int mode, int quality, int fps, boolean automatic) {
            this.revision = revision;
            this.mode = mode;
            this.quality = quality;
            this.fps = fps;
            this.automatic = automatic;
        }
    }

    private static final class Connection {
        final Socket base;
        final SSLSocket socket;
        private final DataOutputStream output;
        private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(64);
        private final Thread writer;
        private volatile boolean stopped;

        Connection(Socket base, SSLSocket socket, DataOutputStream output) {
            this.base = base;
            this.socket = socket;
            this.output = output;
            writer = thread(this::write, "pc-command-writer");
        }

        void start() { writer.start(); }

        void sendView(View view) {
            enqueue(out -> {
                out.writeInt(Wire.VIEW);
                out.writeInt(view.revision);
                out.writeInt(view.mode);
                out.writeInt(view.quality);
                out.writeInt(view.fps);
                out.writeBoolean(view.automatic);
            }, false);
        }

        boolean enqueue(Packet packet, boolean movement) {
            if (stopped || socket.isClosed()) return false;
            if (movement && queue.size() >= 12) return true;
            if (queue.offer(packet)) return true;
            writer.interrupt();
            return false;
        }

        private void write() {
            try {
                while (!stopped && !socket.isClosed()) {
                    Packet packet = queue.take();
                    packet.write(output);
                    for (int i = 0; i < 8; i++) {
                        packet = queue.poll();
                        if (packet == null) break;
                        packet.write(output);
                    }
                    output.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                if (!stopped) close(base);
            }
        }

        void stop() { stopped = true; writer.interrupt(); queue.clear(); }
    }

    private interface Packet { void write(DataOutputStream output) throws IOException; }

    private static final class PairingFailure extends Exception { PairingFailure(String reason) { super(reason); } }

    private static final class ReplacedIdentity extends CertificateException { }

    private static final class LocalTrust implements X509TrustManager {
        private final String expected;
        LocalTrust(String expected) { this.expected = expected; }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authentication) throws CertificateException {
            if (chain == null || chain.length != 1) throw new CertificateException("Se esperaba la identidad local de Windows");
            X509Certificate leaf = chain[0];
            try {
                leaf.checkValidity();
                leaf.verify(leaf.getPublicKey());
                if (expected != null && !expected.equals(Wire.fingerprint(leaf))) throw new ReplacedIdentity();
            } catch (CertificateException invalid) { throw invalid; }
            catch (GeneralSecurityException invalid) { throw new CertificateException(invalid); }
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authentication) throws CertificateException {
            throw new CertificateException("Este dispositivo es el receptor");
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
