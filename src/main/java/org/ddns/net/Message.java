package org.ddns.net;

import org.ddns.bc.SignatureUtil;

import java.security.PublicKey;

/**
 * A universal wrapper for all network messages.
 * Contains metadata like the sender's identity and the message type,
 * with the actual data (e.g., a Transaction JSON) in the payload.
 */
public class Message {
    public final MessageType type;
    public final String senderIp;
    public final String senderPublicKey; // Base64 string
    public String payload; // JSON string of the actual data
    public byte[] signature;

    public Message(MessageType type, String senderIp, PublicKey senderPublicKey, String payload) {
        this.type = type;
        this.senderIp = senderIp;
        this.senderPublicKey = SignatureUtil.getStringFromKey(senderPublicKey);
        this.payload = payload;
    }

    public String toHashString() {
        return type.toString() + senderIp + senderPublicKey + payload;
    }
}