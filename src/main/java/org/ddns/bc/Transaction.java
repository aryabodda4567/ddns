package org.ddns.bc;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;

/**
 * Represents a single, signed transaction in the dDNS blockchain.
 * This class is designed to be flexible to support various transaction types
 * using a payload map.
 */
public class Transaction {

    private final String hash;
    private final PublicKey senderPublicKey;
    private final TransactionType type;
    private final String domainName; // The primary domain this transaction affects
    private final Map<String, String> payload; // Flexible data for different tx types
    private final long timestamp;
    private byte[] signature;


    /**
     * Constructor for a new Transaction.
     *
     * @param senderPublicKey The public key of the transaction creator.
     * @param type            The type of the transaction (e.g., REGISTER, TRANSFER_OWNERSHIP).
     * @param domainName      The target domain name.
     * @param payload         A map containing key-value data specific to the transaction type.
     *                        - For REGISTER: {"owner": "...", "ttl": "...", "ip": "..."}
     *                        - For TRANSFER: {"newOwner": "..."}
     */
    public Transaction(PublicKey senderPublicKey, TransactionType type, String domainName, Map<String, String> payload) {
        this.senderPublicKey = senderPublicKey;
        this.type = type;
        this.domainName = domainName;
        this.payload = payload;
        this.timestamp = System.currentTimeMillis();
        this.hash = calculateHash();
    }

    /**
     * Calculates the unique hash of the transaction's content.
     * This hash is what gets signed.
     *
     * @return A SHA-256 hash string.
     */
    public String calculateHash() {
        // The sorted nature of a TreeMap ensures the hash is always consistent
        // regardless of the order in which payload items were added.
        String payloadJson = new com.google.gson.Gson().toJson(new java.util.TreeMap<>(payload));
        return SignatureUtil.applySha256(
                SignatureUtil.getStringFromKey(senderPublicKey) +
                        type.toString() +
                        domainName +
                        payloadJson +
                        timestamp
        );
    }

    /**
     * Signs the transaction using the sender's private key.
     *
     * @param privateKey The private key to sign with.
     */
    public void sign(PrivateKey privateKey) {
        this.signature = SignatureUtil.sign(privateKey, this.hash);
    }

    /**
     * Verifies the transaction's signature.
     *
     * @return true if the signature is valid, false otherwise.
     */
    public boolean verifySignature() {
        return SignatureUtil.verify(senderPublicKey, signature, this.hash);
    }

    // --- Getters ---
    public String getHash() {
        return hash;
    }

    public PublicKey getSenderPublicKey() {
        return senderPublicKey;
    }

    public TransactionType getType() {
        return type;
    }

    public String getDomainName() {
        return domainName;
    }

    public Map<String, String> getPayload() {
        return payload;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
