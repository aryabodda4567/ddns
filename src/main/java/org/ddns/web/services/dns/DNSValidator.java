package org.ddns.web.services.dns;

import org.ddns.dns.DNSModel;
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
            case 2 -> { // NS
                if (!isValidDomain(rdata)) {
                    throw new IllegalArgumentException("Invalid NS target: " + rdata);
                }
            }
            case 15 -> { // MX
                // Accept either host only or "priority host"
                if (!isValidMx(rdata)) {
                    throw new IllegalArgumentException("Invalid MX value: " + rdata);
                }
            }
            case 16 -> { // TXT
                // non-empty already enforced above
            }
            case 12 -> { // PTR
                if (!isValidDomain(rdata)) {
                    throw new IllegalArgumentException("Invalid PTR target: " + rdata);
                }
            }
            case 6 -> { // SOA
                // SOA is usually composite; allow non-empty string payload for now
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
            case 2 -> { // NS
                if (!isValidDomain(rdata)) {
                    throw new IllegalArgumentException("Invalid NS target: " + rdata);
                }
            }
            case 15 -> { // MX
                if (!isValidMx(rdata)) {
                    throw new IllegalArgumentException("Invalid MX value: " + rdata);
                }
            }
            case 12 -> { // PTR
                if (!isValidDomain(rdata)) {
                    throw new IllegalArgumentException("Invalid PTR target: " + rdata);
                }
            }
            case 16, 6 -> {
                // TXT / SOA: non-empty already validated
            }
            default -> {
                // For future types
                // Accept but require non-empty rdata
            }
        }
    }

    private static boolean isValidType(int type) {
        return type == 1    // A
                || type == 2    // NS
                || type == 5    // CNAME
                || type == 6    // SOA
                || type == 12   // PTR
                || type == 15   // MX
                || type == 16   // TXT
                || type == 28;  // AAAA
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

    private static boolean isValidMx(String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length == 1) {
            return isValidDomain(parts[0]);
        }

        if (parts.length == 2) {
            try {
                int preference = Integer.parseInt(parts[0]);
                if (preference < 0 || preference > 65535) return false;
            } catch (NumberFormatException e) {
                return false;
            }
            return isValidDomain(parts[1]);
        }

        return false;
    }







}
