package com.proyector.sender;

import com.proyector.shared.Wire;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

final class HostNetwork {
    record Route(InetAddress address, InetAddress broadcast, String type, String label) { }

    private HostNetwork() { }

    static boolean usbInterface(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("usb") || normalized.contains("rndis") || normalized.contains("ncm")
                || (normalized.contains("ndis") && (normalized.contains("remote") || normalized.contains("remoto")));
    }

    static List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                String description = network.getDisplayName();
                String name = (network.getName() + " " + description).toLowerCase(Locale.ROOT);
                for (InterfaceAddress candidate : network.getInterfaceAddresses()) {
                    InetAddress address = candidate.getAddress();
                    if (Wire.isVpnAddress(address) && name.contains("tailscale")) {
                        routes.add(new Route(address, null, "VPN", "Tailscale"));
                    } else if (Wire.isLocalAddress(address) && candidate.getBroadcast() != null) {
                        boolean usb = usbInterface(name);
                        boolean wireless = name.contains("wireless") || name.contains("wi-fi") || name.contains("wlan");
                        routes.add(new Route(address, candidate.getBroadcast(), usb ? "USB" : "LAN",
                                usb ? "Cable USB" : wireless ? "Wi-Fi" : "Red local"));
                    }
                }
            }
        } catch (SocketException ignored) { }
        routes.sort(Comparator.comparing(Route::type).thenComparing(route -> route.address().getHostAddress()));
        return routes;
    }
}
