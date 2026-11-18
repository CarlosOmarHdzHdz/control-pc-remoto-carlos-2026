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

    static String localLabel(String name, boolean virtual) {
        if (virtual || name.contains("virtual") || name.contains("vethernet") || name.contains("hyper-v")
                || name.contains("vmware") || name.contains("wsl") || name.contains("host-only")) return "Red virtual";
        if (name.contains("wireless") || name.contains("wi-fi") || name.contains("wlan")) return "Wi-Fi";
        return "Red local";
    }

    static int routePriority(Route route) {
        int virtual = route.label().equals("Red virtual") ? 2 : 0;
        return virtual + (route.address().isLinkLocalAddress() ? 1 : 0);
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
                        routes.add(new Route(address, candidate.getBroadcast(), usb ? "USB" : "LAN",
                                usb ? "Cable USB" : localLabel(name, network.isVirtual())));
                    }
                }
            }
        } catch (SocketException ignored) { }
        routes.sort(Comparator.comparing(Route::type).thenComparingInt(HostNetwork::routePriority)
                .thenComparing(route -> route.address().getHostAddress()));
        return routes;
    }
}
