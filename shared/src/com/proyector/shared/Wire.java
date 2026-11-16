package com.proyector.shared;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.cert.Certificate;

/** Messages on the authenticated Control de PC connection. */
public final class Wire {
    public static final int PORT = 5050;
    public static final int DISCOVERY_PORT = 5051;
    public static final String DISCOVERY_PREFIX = "CONTROLPC/2";
    public static final int MAGIC = 0x43504332;
    public static final int MAX_FRAME = 3 * 1024 * 1024;
    public static final int COORDINATE_MAX = 1_000_000;

    public static final int OK = 0;
    public static final int BAD_PIN = 1;
    public static final int TRY_LATER = 2;
    public static final int IN_USE = 3;

    // Windows to Android.
    public static final int NOTICE = 1;
    public static final int FRAME = 2;
    public static final int PONG = 3;
    public static final int DISPLAYS = 4;

    // Android to Windows. Touch commands include the view revision and the last displayed frame.
    public static final int SLEEP = 1;
    public static final int SHUTDOWN = 2;
    public static final int MUTE = 3;
    public static final int VOLUME_UP = 4;
    public static final int MOVE = 5;
    public static final int DOWN = 6;
    public static final int UP = 7;
    public static final int RIGHT_CLICK = 8;
    public static final int SCROLL = 9;
    public static final int LOCK = 10;
    public static final int RESTART = 11;
    public static final int VOLUME_DOWN = 12;
    public static final int RELATIVE = 13;
    public static final int TEXT = 14;
    public static final int STATUS = 15;
    public static final int VOLUME_SET = 16;
    public static final int VIEW = 17;
    public static final int FRAME_ACK = 18;
    public static final int PING = 19;

    public static final int PAUSED = 0;
    public static final int DESKTOP = 1;
    public static final int SECOND_DISPLAY = 2;
    public static final String[] RESOLUTIONS = {"360p", "540p", "720p", "1080p"};
    public static final int[] WIDTHS = {640, 960, 1280, 1920};
    public static final int[] HEIGHTS = {360, 540, 720, 1080};
    public static final int[] FRAME_RATES = {12, 20, 30, 45, 60};

    private Wire() { }

    public static boolean isTouch(int operation) {
        return (operation >= MOVE && operation <= SCROLL) || operation == RELATIVE || operation == TEXT;
    }

    public static boolean isVpnAddress(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] octets = address.getAddress();
        return octets[0] == 100 && (octets[1] & 0xC0) == 0x40;
    }

    public static boolean isLocalAddress(InetAddress address) {
        return address instanceof Inet4Address && (address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isLoopbackAddress());
    }

    public static InetAddress parseAddress(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        try {
            for (int i = 0; i < bytes.length; i++) {
                if (!parts[i].matches("[0-9]{1,3}")) return null;
                int number = Integer.parseInt(parts[i]);
                if (number > 255) return null;
                bytes[i] = (byte) number;
            }
            return InetAddress.getByAddress(bytes);
        } catch (java.net.UnknownHostException impossible) {
            return null;
        }
    }

    public static String fingerprint(Certificate certificate) throws java.security.GeneralSecurityException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    public static String identityCode(String fingerprint) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 20; i += 5) {
            if (i > 0) result.append(' ');
            result.append(fingerprint.substring(i, i + 5).toUpperCase(java.util.Locale.ROOT));
        }
        return result.toString();
    }

    public static String displayName(String value) {
        String clean = value.replaceAll("[^\\p{L}\\p{N} ._()-]", "").trim();
        if (clean.isEmpty()) return "PC Windows";
        return clean.substring(0, Math.min(clean.length(), 40));
    }
}
