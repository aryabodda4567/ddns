package org.ddns.web.services.config;

import org.ddns.bc.SignatureUtil;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.PrivateKey;
import java.util.Base64;

public final class JoinRequestValidator {

    private static final int MAX_KEY_LENGTH = 20_000; // allow DER + PEM
    private static final int MAX_HOST_LENGTH = 255;

    private JoinRequestValidator() {}

    public static void validate(String bootstrapHost, String privateKeyString, String username, String password) {
        validateHost(bootstrapHost);
        validatePrivateKey(privateKeyString);
        validateCredentials(username, password);
    }

    public static void validate(String bootstrapHost, String privateKeyString) {
        validateHost(bootstrapHost);
        validatePrivateKey(privateKeyString);
    }

    private static void validateHost(String host) {

        if (host == null || host.trim().isEmpty())
            throw new IllegalArgumentException("Bootstrap IP/host is required");

        host = host.trim();

        if (host.length() > MAX_HOST_LENGTH)
            throw new IllegalArgumentException("Bootstrap IP/host too long");

        // Convert IDN (unicode domains) to ASCII
        try {
            host = IDN.toASCII(host);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid hostname format");
        }

        // Must resolve
        try {
            InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Bootstrap host is not resolvable");
        }
    }

    private static void validatePrivateKey(String key) {

        if (key == null || key.trim().isEmpty())
            throw new IllegalArgumentException("Private key is required");

        String trimmed = key.trim();

        if (trimmed.length() > MAX_KEY_LENGTH)
            throw new IllegalArgumentException("Private key too large");

        // Remove PEM armor if present
        String normalized = stripPemIfPresent(trimmed);

        // Must be valid Base64
        if (!isBase64(normalized))
            throw new IllegalArgumentException("Private key is not valid Base64 or PEM");

        // Hard validation: try parsing using your crypto layer
        try {
            PrivateKey pk = SignatureUtil.getPrivateKeyFromString(trimmed);
            if (pk == null)
                throw new IllegalArgumentException("Private key could not be parsed");
        } catch (Exception e) {
            throw new IllegalArgumentException("Private key is invalid or corrupted");
        }
    }

    private static String stripPemIfPresent(String key) {
        if (key.contains("BEGIN")) {
            return key
                    .replaceAll("-----BEGIN ([A-Z ]*)-----", "")
                    .replaceAll("-----END ([A-Z ]*)-----", "")
                    .replaceAll("\\s", "");
        }
        return key.replaceAll("\\s", "");
    }

    private static boolean isBase64(String s) {
        try {
            Base64.getDecoder().decode(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void validateCredentials(String username, String password) {
        if (username == null || username.isBlank())
            throw new IllegalArgumentException("Username is required");

        if (username.trim().length() < 3 || username.trim().length() > 64)
            throw new IllegalArgumentException("Username must be between 3 and 64 characters");

        if (password == null || password.isBlank())
            throw new IllegalArgumentException("Password is required");

        if (password.trim().length() < 6 || password.trim().length() > 128)
            throw new IllegalArgumentException("Password must be between 6 and 128 characters");
    }
}
