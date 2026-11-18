package com.proyector.sender;

import com.proyector.shared.Wire;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class HostServer implements AutoCloseable {
    interface Events {
        void link(String status, boolean connected);
        void video(String detail);
    }

    private final SSLServerSocket listener;
    private final HostIdentity identity;
    private final String computer;
    private final Supplier<Displays.Settings> settings;
    private final WindowsControl controls;
    private final Events events;
    private final Semaphore slots = new Semaphore(5);
    private final Set<SSLSocket> sockets = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Integer> failuresByAddress = new HashMap<>();
    private long resetFailuresAt = System.nanoTime();
    private int totalFailures;
    private final String pin;
    private volatile boolean closed;
    private Thread discovery;
    private StreamSession active;
    private String activeToken = "";

    HostServer(HostIdentity identity, int port, String computer, Supplier<Displays.Settings> settings,
               WindowsControl controls, Events events) throws IOException {
        this(identity, new InetSocketAddress("0.0.0.0", port), computer, settings, controls, events);
    }

    HostServer(HostIdentity identity, InetSocketAddress bind, String computer, Supplier<Displays.Settings> settings,
               WindowsControl controls, Events events) throws IOException {
        this.identity = identity;
        this.events = events;
        this.computer = computer;
        this.settings = settings;
        this.controls = controls;
        pin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        listener = (SSLServerSocket) identity.context.getServerSocketFactory().createServerSocket();
        try {
            listener.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            listener.setReuseAddress(true);
            listener.bind(bind, 12);
        } catch (IOException | RuntimeException error) {
            listener.close();
            throw error;
        }
    }

    String pin() { return pin; }
    int port() { return listener.getLocalPort(); }

    void start() {
        WindowsControl.daemon(this::accept, "pc-accept").start();
        if (!listener.getInetAddress().isLoopbackAddress()) {
            discovery = WindowsControl.daemon(this::advertise, "pc-discovery");
            discovery.start();
        }
    }

    private void accept() {
        while (!closed) {
            try {
                SSLSocket socket = (SSLSocket) listener.accept();
                var address = socket.getInetAddress();
                if (closed || (!Wire.isLocalAddress(address) && !Wire.isVpnAddress(address)) || !slots.tryAcquire()) {
                    socket.close();
                    continue;
                }
                sockets.add(socket);
                if (closed) {
                    sockets.remove(socket);
                    slots.release();
                    socket.close();
                    continue;
                }
                WindowsControl.daemon(() -> connect(socket), "pc-link").start();
            } catch (IOException error) {
                if (!closed) events.link("El puerto dejó de responder. Detén e inicia el emisor.", false);
                return;
            }
        }
    }

    private void connect(SSLSocket socket) {
        StreamSession session = null;
        try (socket) {
            socket.setSoTimeout(6500);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSendBufferSize(96 * 1024);
            socket.startHandshake();
            socket.setSoTimeout(90_000);
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 8192));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 32 * 1024));
            if (input.readInt() != Wire.MAGIC) return;
            String suppliedPin = input.readUTF();
            String previousToken = input.readUTF();
            if (suppliedPin.length() != 6 || previousToken.length() > 48) return;
            socket.setSoTimeout(12_000);
            String source = socket.getInetAddress().getHostAddress();
            StreamSession previous;
            String token;
            StreamSession next = new StreamSession(socket, input, output, settings, controls, detail -> {
                synchronized (this) { if (active != null && active.uses(socket) && !closed) events.video(detail); }
            });
            synchronized (this) {
                int authorization = authorize(source, suppliedPin, previousToken);
                if (authorization != Wire.OK) {
                    output.writeInt(authorization);
                    output.flush();
                    return;
                }
                if (closed) return;
                previous = active;
                active = session = next;
                if (!equal(previousToken, activeToken) || activeToken.isEmpty()) {
                    byte[] secret = new byte[20];
                    random.nextBytes(secret);
                    activeToken = Wire.ROUTE_TOKEN_PREFIX + HexFormat.of().formatHex(secret);
                }
                token = activeToken;
            }
            if (previous != null) previous.close();
            output.writeInt(Wire.OK);
            output.writeUTF(computer);
            output.writeUTF(token);
            output.flush();
            synchronized (this) {
                if (active == session && !closed) {
                    events.link("Android conectado · " + source, true);
                    events.video("Imagen en pausa");
                }
            }
            session.run();
        } catch (IOException ignored) {
            // The phone retries a fresh TLS connection on brief network failures.
        } finally {
            sockets.remove(socket);
            slots.release();
            synchronized (this) {
                if (session != null && session == active) {
                    active = null;
                    if (!closed) {
                        events.link("Esperando Android", false);
                        events.video("Imagen en pausa");
                    }
                }
            }
        }
    }

    private int authorize(String address, String guess, String token) {
        long now = System.nanoTime();
        if (now - resetFailuresAt >= 0) {
            failuresByAddress.clear();
            totalFailures = 0;
            resetFailuresAt = now + TimeUnit.MINUTES.toNanos(1);
        }
        boolean resuming = !activeToken.isEmpty() && equal(activeToken, token);
        int failures = failuresByAddress.getOrDefault(address, 0);
        if (!resuming && (failures >= 5 || totalFailures >= 25)) return Wire.TRY_LATER;
        if (!equal(pin, guess)) {
            if (totalFailures < 25) {
                failuresByAddress.put(address, failures + 1);
                totalFailures++;
            }
            return Wire.BAD_PIN;
        }
        if (active != null && !resuming) return Wire.IN_USE;
        failuresByAddress.remove(address);
        return Wire.OK;
    }

    private void advertise() {
        for (; !closed; ) {
            for (HostNetwork.Route route : HostNetwork.routes()) {
                if (route.broadcast() == null) continue;
                String line = String.join("|", Wire.DISCOVERY_PREFIX, Integer.toString(listener.getLocalPort()),
                        identity.fingerprint, route.type(), computer);
                byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                try (DatagramSocket udp = new DatagramSocket(new InetSocketAddress(route.address(), 0))) {
                    udp.setBroadcast(true);
                    udp.send(new DatagramPacket(bytes, bytes.length, route.broadcast(), Wire.DISCOVERY_PORT));
                } catch (IOException ignored) { }
            }
            try { Thread.sleep(1200); }
            catch (InterruptedException stop) { return; }
        }
    }

    private static boolean equal(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    @Override public void close() {
        closed = true;
        if (discovery != null) discovery.interrupt();
        try { listener.close(); } catch (IOException ignored) { }
        StreamSession current;
        synchronized (this) { current = active; }
        if (current != null) current.close();
        for (SSLSocket socket : sockets) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
