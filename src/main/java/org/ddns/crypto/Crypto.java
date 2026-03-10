package org.ddns.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.util.Base64;

public class Crypto {

    // ==========================
    // Derive AES key using ECDH
    // ==========================
    public static SecretKeySpec deriveAESKey(
            PrivateKey myPrivate,
            PublicKey otherPublic) throws Exception {

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(myPrivate);
        ka.doPhase(otherPublic, true);

        byte[] sharedSecret = ka.generateSecret();

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] key = sha.digest(sharedSecret);

        return new SecretKeySpec(key, 0, 16, "AES");
    }

    // ==========================
    // AES Encrypt
    // ==========================
    public static String aesEncrypt(
            String data,
            SecretKeySpec key) throws Exception {

        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, key);

        byte[] encrypted = cipher.doFinal(data.getBytes());

        return Base64.getEncoder().encodeToString(encrypted);
    }

    // ==========================
    // AES Decrypt
    // ==========================
    public static String aesDecrypt(
            String cipherText,
            SecretKeySpec key) throws Exception {

        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, key);

        byte[] decoded = Base64.getDecoder().decode(cipherText);

        byte[] decrypted = cipher.doFinal(decoded);

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
    // Encode Public Key (for network transfer)
    // ==========================
    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    // ==========================
    // Decode Public Key
    // ==========================
    public static PublicKey decodePublicKey(String key) throws Exception {

        byte[] bytes = Base64.getDecoder().decode(key);

        KeyFactory kf = KeyFactory.getInstance("EC");

        return kf.generatePublic(new java.security.spec.X509EncodedKeySpec(bytes));
    }
}