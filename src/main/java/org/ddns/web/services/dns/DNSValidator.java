package org.ddns.web.services.dns;

import org.ddns.dns.DNSModel;
import org.ddns.dns.RecordType;

import java.net.InetAddress;

public final class DNSValidator {

    public static void validateForCreate(DNSModel model) {

        if (model == null) {
            throw new IllegalArgumentException("DNS model is null");
        }

        // 1. Name validation
        if (model.getName() == null || model.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Domain name is empty");
        }

        String name = model.getName().trim();

        if (!isValidDomain(name)) {
            throw new IllegalArgumentException("Invalid domain name: " + name);
        }

        // 2. Type validation
        int type = model.getType();
        if (!isValidType(type)) {
            throw new IllegalArgumentException("Invalid DNS record type: " + type);
        }

        // 3. TTL validation
        if (model.getTtl() <= 0) {
            throw new IllegalArgumentException("TTL must be > 0");
        }

        // 4. RDATA validation
        String rdata = model.getRdata();
        if (rdata == null || rdata.trim().isEmpty()) {
            throw new IllegalArgumentException("RDATA is empty");
        }

        rdata = rdata.trim();

        switch (type) {
            case 1 -> { // A
                if (!isValidIPv4(rdata)) {
                    throw new IllegalArgumentException("Invalid IPv4 address: " + rdata);
                }
            }
            case 28 -> { // AAAA
                if (!isValidIPv6(rdata)) {
                    throw new IllegalArgumentException("Invalid IPv6 address: " + rdata);
                }
            }
            case 5 -> { // CNAME
                if (!isValidDomain(rdata)) {
                    throw new IllegalArgumentException("Invalid CNAME target: " + rdata);
                }
            }
            default -> {
                // Should never happen because isValidType blocks it
                throw new IllegalArgumentException("Unsupported DNS record type: " + type);
            }
        }
    }


    private static void validateIP(String ip) {
        try {
            InetAddress.getByName(ip);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid IP address: " + ip);
        }
    }

    private static void validateDomain(String name) {
        if (name.length() > 253) {
            throw new IllegalArgumentException("Domain name too long: " + name);
        }
        // simple check
        if (!name.contains(".")) {
            throw new IllegalArgumentException("Invalid domain name: " + name);
        }
    }
    public static void validateForUpdate(DNSModel model) {

        if (model == null) {
            throw new IllegalArgumentException("DNS model is null");
        }

        // 1. Name validation
        if (model.getName() == null || model.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Domain name is empty");
        }

        String name = model.getName().trim();

        if (!isValidDomain(name)) {
            throw new IllegalArgumentException("Invalid domain name: " + name);
        }

        // 2. Type validation
        int type = model.getType();
        if (!isValidType(type)) {
            throw new IllegalArgumentException("Invalid DNS record type: " + type);
        }

        // 3. TTL validation
        if (model.getTtl() <= 0) {
            throw new IllegalArgumentException("TTL must be > 0");
        }

        // 4. RDATA validation
        String rdata = model.getRdata();
        if (rdata == null || rdata.trim().isEmpty()) {
            throw new IllegalArgumentException("RDATA is empty");
        }

        rdata = rdata.trim();

        switch (type) {
            case 1 -> { // A
                if (!isValidIPv4(rdata)) {
                    throw new IllegalArgumentException("Invalid IPv4 address: " + rdata);
                }
            }
            case 28 -> { // AAAA
                if (!isValidIPv6(rdata)) {
                    throw new IllegalArgumentException("Invalid IPv6 address: " + rdata);
                }
            }
            case 5 -> { // CNAME
                if (!isValidDomain(rdata)) {
                    throw new IllegalArgumentException("Invalid CNAME target: " + rdata);
                }
            }
            default -> {
                // For future types
                // Accept but require non-empty rdata
            }
        }
    }

    private static boolean isValidType(int type) {
        return type == 1   // A
                || type == 28  // AAAA
                || type == 5;  // CNAME (optional, but good)
    }

    private static boolean isValidDomain(String domain) {
        if (domain == null) return false;

        String d = domain.trim().toLowerCase();

        if (d.length() > 253) return false;

        // Very safe domain regex
        return d.matches("^(?=.{1,253}$)([a-z0-9]+(-[a-z0-9]+)*\\.)+[a-z]{2,}$");
    }

    private static boolean isValidIPv4(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;

            for (String p : parts) {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isValidIPv6(String ip) {
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
            return addr instanceof java.net.Inet6Address;
        } catch (Exception e) {
            return false;
        }
    }







}
