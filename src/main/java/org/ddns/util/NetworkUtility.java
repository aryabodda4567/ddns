package org.ddns.util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class NetworkUtility {

    /**
     * Returns the system's local IPv4 address (non-loopback).
     */
    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                // Skip inactive or loopback interfaces
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    // Skip loopback and IPv6
                    if (!addr.isLoopbackAddress() && !addr.getHostAddress().contains(":")) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting IP address: " + e.getMessage());
        }
        return "Unknown";
    }

    /**
     * Returns the system's hostname.
     */
    public static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "Unknown";
        }
    }
}

