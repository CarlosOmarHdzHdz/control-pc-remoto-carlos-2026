package com.proyector.sender;

import com.proyector.shared.Wire;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

final class HostIdentity {
    private static final String ALIAS = "control-pc";
    private static final char[] LOCAL_STORE_PASSWORD = new char[0];
    final SSLContext context;
    final String fingerprint;

    private HostIdentity(KeyStore store) throws GeneralSecurityException {
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        manager.init(store, LOCAL_STORE_PASSWORD);
        context = SSLContext.getInstance("TLS");
        context.init(manager.getKeyManagers(), null, null);
        fingerprint = Wire.fingerprint(store.getCertificate(ALIAS));
    }

    static HostIdentity load() throws GeneralSecurityException, IOException {
        String local = System.getenv("LOCALAPPDATA");
        Path profile = local == null ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(local);
        return load(profile.resolve("Control de PC").resolve("identity.p12"));
    }

    static HostIdentity load(Path file) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        if (Files.notExists(file)) {
            store.load(null, LOCAL_STORE_PASSWORD);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            store.setKeyEntry(ALIAS, pair.getPrivate(), LOCAL_STORE_PASSWORD,
                    new Certificate[]{certificate(pair)});
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "identity-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    store.store(output, LOCAL_STORE_PASSWORD);
                }
                try { Files.move(temporary, file); }
                catch (FileAlreadyExistsException concurrentStart) { /* Read the existing identity below. */ }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        try (InputStream input = Files.newInputStream(file)) {
            store.load(input, LOCAL_STORE_PASSWORD);
        }
        return new HostIdentity(store);
    }

    private static Certificate certificate(KeyPair pair) throws GeneralSecurityException {
        // Minimal self-signed X.509 leaf. JSSE performs the TLS handshake and the app pins this leaf.
        byte[] ecdsaSha256 = der(0x30, der(0x06,
                new byte[]{0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x04, 0x03, 0x02}));
        byte[] name = new X500Principal("CN=Control de PC").getEncoded();
        Instant now = Instant.now();
        byte[] serial = new BigInteger(127, new SecureRandom()).setBit(126).toByteArray();
        byte[] validity = der(0x30, time(now.minus(3, ChronoUnit.DAYS)), time(now.plus(3650, ChronoUnit.DAYS)));
        byte[] body = der(0x30, der(0xa0, der(0x02, new byte[]{2})), der(0x02, serial),
                ecdsaSha256, name, validity, name, pair.getPublic().getEncoded());
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate());
        signature.update(body);
        byte[] encoded = der(0x30, body, ecdsaSha256, der(0x03, new byte[]{0}, signature.sign()));
        return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
    }

    private static byte[] time(Instant time) {
        boolean before2050 = time.atZone(ZoneOffset.UTC).getYear() < 2050;
        String pattern = before2050 ? "yyMMddHHmmss'Z'" : "uuuuMMddHHmmss'Z'";
        String value = DateTimeFormatter.ofPattern(pattern, java.util.Locale.ROOT).withZone(ZoneOffset.UTC).format(time);
        return der(before2050 ? 0x17 : 0x18, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int size = 0;
        for (byte[] part : parts) size += part.length;
        bytes.write(tag);
        if (size < 128) bytes.write(size);
        else if (size < 256) { bytes.write(0x81); bytes.write(size); }
        else { bytes.write(0x82); bytes.write(size >>> 8); bytes.write(size); }
        for (byte[] part : parts) bytes.writeBytes(part);
        return bytes.toByteArray();
    }
}
