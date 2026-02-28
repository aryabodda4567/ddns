package org.ddns.web.services.config;

import org.ddns.bc.SignatureUtil;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.PrivateKey;
import java.util.Base64;

/**
 * Validates join request input from web layer.
 */
public final class JoinRequestValidator {

    private static final int MAX_KEY_LENGTH = 20_000;
    private static final int MAX_HOST_LENGTH = 255;
    private static final int MIN_USERNAME_LENGTH = 3;
    private static final int MAX_USERNAME_LENGTH = 64;
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private JoinRequestValidator() {
    }

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
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Bootstrap IP/host is required");
        }

        String normalizedHost = host.trim();
        if (normalizedHost.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("Bootstrap IP/host too long");
        }

        try {
            normalizedHost = IDN.toASCII(normalizedHost);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid hostname format");
        }

        try {
            InetAddress.getByName(normalizedHost);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Bootstrap host is not resolvable");
        }
    }

    private static void validatePrivateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Private key is required");
        }

        String trimmedKey = key.trim();
        if (trimmedKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Private key too large");
        }

        String normalized = stripPemIfPresent(trimmedKey);
        if (!isBase64(normalized)) {
            throw new IllegalArgumentException("Private key is not valid Base64 or PEM");
        }

        try {
            PrivateKey parsedKey = SignatureUtil.getPrivateKeyFromString(trimmedKey);
            if (parsedKey == null) {
                throw new IllegalArgumentException("Private key could not be parsed");
            }
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

    private static boolean isBase64(String value) {
        try {
            Base64.getDecoder().decode(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void validateCredentials(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }

        int usernameLength = username.trim().length();
        if (usernameLength < MIN_USERNAME_LENGTH || usernameLength > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("Username must be between 3 and 64 characters");
        }

        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }

        int passwordLength = password.trim().length();
        if (passwordLength < MIN_PASSWORD_LENGTH || passwordLength > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be between 6 and 128 characters");
        }
    }
}
