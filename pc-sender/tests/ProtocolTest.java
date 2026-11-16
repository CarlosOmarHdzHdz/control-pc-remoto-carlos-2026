package com.proyector.sender;

import com.proyector.shared.Wire;

import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ProtocolTest {
    private static final HostServer.Events QUIET = new HostServer.Events() {
        @Override public void link(String status, boolean connected) { }
        @Override public void video(String detail) { }
    };

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("control-pc-test-");
        Path key = directory.resolve("qa.p12");
        try {
            HostIdentity identity = HostIdentity.load(key);
            check(identity.fingerprint.equals(HostIdentity.load(key).fingerprint), "La identidad debe sobrevivir al reinicio");
            if (args.length == 1 && "--android".equals(args[0])) {
                androidHost(identity);
                return;
            }
            captureGeometry();
            automaticQuality();
            WindowsControl audio = new WindowsControl();
            CompletableFuture<String> status = new CompletableFuture<>();
            audio.request(Wire.STATUS, 0, () -> true,
                    (success, data) -> status.complete(success ? data : "ERROR " + data));
            String reports = status.get(10, TimeUnit.SECONDS);
            check(reports.matches("STAT:(?:NA|[0-9]{1,3}):[01]:(?:NA|[0-9]{1,3}):[01]"), "Estado nativo inesperado: " + reports);
            try (HostServer host = host(identity, audio)) {
                host.start();
                secureSession(identity, host);
            }
            try (HostServer host = host(identity, audio)) {
                host.start();
                String incorrect = host.pin().equals("000000") ? "999999" : "000000";
                for (int attempt = 0; attempt < 6; attempt++) {
                    try (Peer client = peer(identity, host, incorrect, "")) {
                        check(client.authorization == (attempt < 5 ? Wire.BAD_PIN : Wire.TRY_LATER), "Límite de PIN");
                    }
                }
            }
            System.out.println("Correcto: identidad persistente, TLS, PIN, reconexión, pausa, dos imágenes en tránsito, Full HD, escalado y estado nativo.");
        } finally {
            Files.deleteIfExists(key);
            Files.deleteIfExists(directory);
        }
    }

    private static void captureGeometry() throws Exception {
        check(Arrays.equals(FrameEncoder.fit(2560, 1440, 3), new int[]{1920, 1080}), "Full HD horizontal");
        check(Arrays.equals(FrameEncoder.fit(1440, 2560, 3), new int[]{1080, 1920}), "Full HD vertical");
        check(Arrays.equals(FrameEncoder.fit(1366, 768, 3), new int[]{1366, 768}), "Evitar ampliar");
        BufferedImage source = new BufferedImage(2560, 1440, BufferedImage.TYPE_INT_RGB);
        try (FrameEncoder encoder = new FrameEncoder()) {
            encoder.encode(source, 3, false);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            encoder.writeTo(new DataOutputStream(bytes));
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
            check(decoded.getWidth() == 1920 && decoded.getHeight() == 1080, "JPEG Full HD");
        }
        Rectangle bounds = new Rectangle(-100, 50, 200, 100);
        check(new Point(0, 75).equals(StreamSession.pointOnDisplay(bounds, 500_000, 250_000)), "Mapeo táctil");
        check(StreamSession.pointOnDisplay(bounds, -1, -1) == null, "Panel táctil relativo");
        try { StreamSession.pointOnDisplay(bounds, -2, 0); throw new AssertionError("Coordenada fuera de rango"); }
        catch (IOException expected) { }
    }

    private static void automaticQuality() {
        AdaptiveVideo video = new AdaptiveVideo();
        video.configure(3, 60, true);
        for (int i = 0; i < 5; i++) video.feedback(560, 65);
        check(video.resolution() == 0 && video.fps() == 15, "Reducir carga en red lenta");
        for (int i = 0; i < 160; i++) video.feedback(8, 5);
        check(video.resolution() == 3 && video.fps() == 60, "Recuperar calidad cuando mejora la red");
        video.configure(2, 30, false);
        video.feedback(560, 65);
        check(video.resolution() == 2 && video.fps() == 30, "Respetar ajuste manual");
    }

    private static void secureSession(HostIdentity identity, HostServer host) throws Exception {
        try (Peer client = peer(identity, host, host.pin(), "")) {
            check(client.authorization == Wire.OK && client.token.length() == 48, "Autenticación inicial");
            client.ping();
            client.expectQuiet();
            client.view(1, Wire.DESKTOP);
            int first = client.receiveFrame();
            int second = client.receiveFrame();
            check(second > first, "Secuencia de imágenes");
            client.expectQuiet();
            client.ack(first);
            check(client.receiveFrame() > second, "Reanudar al recibir confirmación");
            try (Peer other = peer(identity, host, host.pin(), "")) {
                check(other.authorization == Wire.IN_USE, "Una segunda PC no toma la sesión activa");
            }
            try (Peer resumed = peer(identity, host, host.pin(), client.token)) {
                check(resumed.authorization == Wire.OK && resumed.token.equals(client.token), "Continuar sesión autenticada");
                resumed.ping();
                resumed.expectQuiet();
            }
        }
    }

    private static HostServer host(HostIdentity identity, WindowsControl audio) throws Exception {
        return new HostServer(identity, new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), "PC de prueba",
                () -> new Displays.Settings("", "", 3, 60), audio, QUIET);
    }

    private static void androidHost(HostIdentity identity) throws Exception {
        try (HostServer host = host(identity, new WindowsControl())) {
            host.start();
            System.out.println("PORT=" + host.port());
            System.out.println("PIN=" + host.pin());
            System.out.println("IDENTITY=" + Wire.identityCode(identity.fingerprint));
            System.out.flush();
            Thread.sleep(TimeUnit.MINUTES.toMillis(12));
        }
    }

    private static Peer peer(HostIdentity identity, HostServer host, String pin, String token) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                throw new CertificateException("Solo servidor");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    check(chain.length == 1 && identity.fingerprint.equals(Wire.fingerprint(chain[0])), "Certificado exacto");
                } catch (java.security.GeneralSecurityException error) { throw new CertificateException(error); }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", host.port());
        socket.setSoTimeout(5000);
        return new Peer(socket, pin, token);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Peer implements AutoCloseable {
        private final SSLSocket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final int authorization;
        private final String token;

        Peer(SSLSocket socket, String pin, String previous) throws IOException {
            this.socket = socket;
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(Wire.MAGIC);
            output.writeUTF(pin);
            output.writeUTF(previous);
            output.flush();
            authorization = input.readInt();
            if (authorization == Wire.OK) {
                check(input.readUTF().equals("PC de prueba"), "Nombre del emisor");
                token = input.readUTF();
            } else token = "";
        }

        void ping() throws IOException {
            output.writeInt(Wire.PING);
            output.writeLong(123);
            output.flush();
            while (true) {
                int packet = input.readInt();
                if (packet == Wire.PONG) { check(input.readLong() == 123, "Respuesta de conectividad"); return; }
                discardStatus(packet);
            }
        }

        void view(int revision, int mode) throws IOException {
            output.writeInt(Wire.VIEW);
            output.writeInt(revision);
            output.writeInt(mode);
            output.writeInt(0);
            output.writeInt(30);
            output.writeBoolean(false);
            output.flush();
        }

        void ack(int sequence) throws IOException { output.writeInt(Wire.FRAME_ACK); output.writeInt(sequence); output.flush(); }

        int receiveFrame() throws IOException {
            while (true) {
                int packet = input.readInt();
                if (packet != Wire.FRAME) { discardStatus(packet); continue; }
                check(input.readInt() == 1, "Revisión de pantalla");
                int sequence = input.readInt();
                int length = input.readInt();
                check(length > 0 && length <= Wire.MAX_FRAME, "Longitud de imagen");
                input.skipNBytes(length);
                return sequence;
            }
        }

        void expectQuiet() throws IOException {
            socket.setSoTimeout(350);
            try {
                while (true) discardStatus(input.readInt());
            } catch (SocketTimeoutException expected) { }
            finally { socket.setSoTimeout(5000); }
        }

        private void discardStatus(int packet) throws IOException {
            if (packet == Wire.NOTICE) { input.readBoolean(); input.readUTF(); }
            else if (packet == Wire.DISPLAYS) { input.readInt(); input.readUTF(); }
            else throw new AssertionError("Mensaje inesperado en estado de pausa o espera: " + packet);
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
