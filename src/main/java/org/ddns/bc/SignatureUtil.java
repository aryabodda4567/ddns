package org.ddns.bc;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * A utility class for handling cryptographic operations like SHA-256 hashing,
 * key generation, digital signatures, and signature verification using ECDSA.
 * This class requires the Bouncy Castle security provider.
 */
public class SignatureUtil {

    private static final String ALGORITHM = "SHA256withECDSA";

    /**
     * Applies the SHA-256 hash algorithm to a given string.
     * @param input The string to be hashed.
     * @return The calculated SHA-256 hash as a hex string.
     */
    public static String applySha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Generates a new Elliptic Curve KeyPair (public and private key).
     * @return A new KeyPair object.
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("ECDSA", "BC");
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            ECGenParameterSpec ecSpec = new ECGenParameterSpec("prime256v1");
            keyGen.initialize(ecSpec, random);
            return keyGen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Signs a string of data using a private key.
     * @param privateKey The private key to sign with.
     * @param data The data to be signed.
     * @return An array of bytes representing the signature.
     */
    public static byte[] sign(PrivateKey privateKey, String data) {
        try {
            Signature ecdsa = Signature.getInstance(ALGORITHM, "BC");
            ecdsa.initSign(privateKey);
            ecdsa.update(data.getBytes());
            return ecdsa.sign();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Verifies a digital signature.
     * @param publicKey The public key corresponding to the private key used for signing.
     * @param signature The signature to be verified.
     * @param data The original data that was signed.
     * @return true if the signature is valid, false otherwise.
     */
    public static boolean verify(PublicKey publicKey, byte[] signature, String data) {
        try {
            Signature ecdsa = Signature.getInstance(ALGORITHM, "BC");
            ecdsa.initVerify(publicKey);
            ecdsa.update(data.getBytes());
            return ecdsa.verify(signature);
        } catch (Exception e) {
            // Can be caused by an invalid signature format
            return false;
        }
    }

    /**
     * A helper method to get a Base64 encoded string from a Key object.
     * @param key The Key (PublicKey or PrivateKey) to encode.
     * @return A Base64 encoded string representation of the key.
     */
    public static String getStringFromKey(Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * A helper method to convert a public key string into PublicKey object
     * @param key The Public key string to convert
     * @return  PublicKey Object
     */
    public static PublicKey getPublicKeyFromString(String key) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(key);
        KeyFactory keyFactory = KeyFactory.getInstance("ECDSA", "BC");
        return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
    }
    /**
     * A helper method to convert a private key string into PrivateKey object
     * @param key The Private key string to convert
     * @return  PrivateKey  Object
     */
    public static PrivateKey getPrivateKeyFromString(String key) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(key);
        KeyFactory keyFactory = KeyFactory.getInstance("ECDSA", "BC");
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }
}