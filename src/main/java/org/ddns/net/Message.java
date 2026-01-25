package org.ddns.net;

import org.ddns.bc.SignatureUtil;
import org.ddns.util.TimeUtil;

import java.security.PublicKey;
import java.util.Arrays;

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
    public static final int  TTL=1500;//TTL in milliseconds
    public final long initTime;

    public Message(MessageType type, String senderIp, PublicKey senderPublicKey, String payload) {
        this.type = type;
        this.senderIp = senderIp;
        if(senderPublicKey!=null)
        this.senderPublicKey = SignatureUtil.getStringFromKey(senderPublicKey);
        else this.senderPublicKey=null;
        this.payload = payload;
        initTime = TimeUtil.getCurrentUnixTimeMillis();
    }

    public String toHashString() {
        return type.toString() + senderIp + senderPublicKey + payload;
    }

    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", senderIp='" + senderIp + '\'' +
                ", senderPublicKey='" + senderPublicKey + '\'' +
                ", payload='" + payload + '\'' +
                ", signature=" + Arrays.toString(signature) +
                ", initTime=" + initTime +
                '}';
    }
}