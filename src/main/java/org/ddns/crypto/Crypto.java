package org.ddns.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class Crypto {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    // ==========================
    // Derive AES key using ECDH
    // ==========================
    public static SecretKeySpec deriveAESKey(
            PrivateKey myPrivate,
            PublicKey otherPublic) throws Exception {

        KeyAgreement ka = KeyAgreement.getInstance("ECDH", "BC");

        ka.init(myPrivate);
        ka.doPhase(otherPublic, true);

        byte[] sharedSecret = ka.generateSecret();

        MessageDigest sha = MessageDigest.getInstance("SHA-256", "BC");
        byte[] key = sha.digest(sharedSecret);

        return new SecretKeySpec(key, 0, 16, "AES");
    }

    // ==========================
    // AES Encrypt (AES-GCM)
    // ==========================
    public static String aesEncrypt(
            String data,
            SecretKeySpec key) throws Exception {

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");

        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv);

        GCMParameterSpec spec = new GCMParameterSpec(128, iv);

        cipher.init(Cipher.ENCRYPT_MODE, key, spec);

        byte[] encrypted = cipher.doFinal(data.getBytes());

        byte[] combined = new byte[iv.length + encrypted.length];

        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    // ==========================
    // AES Decrypt
    // ==========================
    public static String aesDecrypt(
            String cipherText,
            SecretKeySpec key) throws Exception {

        byte[] combined = Base64.getDecoder().decode(cipherText);

        byte[] iv = new byte[12];
        byte[] encrypted = new byte[combined.length - 12];

        System.arraycopy(combined, 0, iv, 0, 12);
        System.arraycopy(combined, 12, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");

        GCMParameterSpec spec = new GCMParameterSpec(128, iv);

        cipher.init(Cipher.DECRYPT_MODE, key, spec);

        byte[] decrypted = cipher.doFinal(encrypted);

        return new String(decrypted);
    }

    // ==========================
    // Encrypt for Receiver
    // ==========================
    public static String encryptForReceiver(
            String message,
            PrivateKey senderPrivate,
            PublicKey receiverPublic) throws Exception {

        SecretKeySpec aesKey = deriveAESKey(senderPrivate, receiverPublic);

        return aesEncrypt(message, aesKey);
    }

    // ==========================
    // Decrypt from Sender
    // ==========================
    public static String decryptFromSender(
            String cipherText,
            PrivateKey receiverPrivate,
            PublicKey senderPublic) throws Exception {

        SecretKeySpec aesKey = deriveAESKey(receiverPrivate, senderPublic);

        return aesDecrypt(cipherText, aesKey);
    }

    // ==========================
    // Encode Public Key
    // ==========================
    public static String encodePublicKey(PublicKey key) {

        return Base64.getEncoder().encodeToString(key.getEncoded());

    }

    // ==========================
    // Decode Public Key
    // ==========================
    public static PublicKey decodePublicKey(String key) throws Exception {

        byte[] bytes = Base64.getDecoder().decode(key);

        KeyFactory kf = KeyFactory.getInstance("EC", "BC");

        return kf.generatePublic(new X509EncodedKeySpec(bytes));

    }
}