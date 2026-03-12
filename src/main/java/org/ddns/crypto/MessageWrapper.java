package org.ddns.crypto;

import org.ddns.net.MessageType;

import java.security.PublicKey;

public class MessageWrapper {
    PublicKey senderPublicKey;
    byte[] signature;
    String encryptedMessage;

    public MessageWrapper(PublicKey senderPublicKey, byte[] signature, String encryptedMessage) {
        this.senderPublicKey = senderPublicKey;
        this.signature = signature;
        this.encryptedMessage = encryptedMessage;
    }

    public MessageWrapper(PublicKey senderPublicKey, byte[] signature, String encryptedMessage, boolean exclude) {
        this.senderPublicKey = senderPublicKey;
        this.signature = signature;
        this.encryptedMessage = encryptedMessage;

    }

    public PublicKey getSenderPublicKey() {
        return senderPublicKey;
    }

    public void setSenderPublicKey(PublicKey senderPublicKey) {
        this.senderPublicKey = senderPublicKey;
    }

    public byte[] getSignature() {
        return signature;
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    public String getEncryptedMessage() {
        return encryptedMessage;
    }

    public void setEncryptedMessage(String encryptedMessage) {
        this.encryptedMessage = encryptedMessage;
    }
}
