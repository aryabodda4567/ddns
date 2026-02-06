package org.ddns.bc;

import org.ddns.constants.Role;
import org.ddns.db.DBUtil;
import org.ddns.dns.DNSModel;
import org.ddns.net.Message;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Represents a single, signed transaction in the dDNS blockchain.
 * This version uses a list of DNSModel objects as the payload, allowing
 * transactions to carry one or more DNS records.
 */
public class Transaction {

    private final String hash;
    private final PublicKey senderPublicKey;
    private final TransactionType type;
    private final List<DNSModel> payload; // List of DNS records related to this transaction
    private final long timestamp;
    private byte[] signature;

    /**
     * Constructor for a new Transaction.
     *
     * @param senderPublicKey The public key of the transaction creator.
     * @param type            The type of the transaction (e.g., REGISTER, UPDATE,
     *                        DELETE).
     * @param payload         A list of DNSModel objects representing DNS records
     *                        involved.
     */
    public Transaction(PublicKey senderPublicKey, TransactionType type, List<DNSModel> payload, long timestamp) {
        this.senderPublicKey = senderPublicKey;
        this.type = type;
        this.payload = payload;
        this.timestamp = timestamp;
        this.hash = calculateHash();
    }

    /**
     * Calculates the unique hash of the transaction's content.
     * The hash includes sender key, type, payload (as JSON), and timestamp.
     *
     * @return SHA-256 hash string representing this transaction.
     */
    public String calculateHash() {
        String payloadJson = new com.google.gson.Gson().toJson(payload);
        return SignatureUtil.applySha256(
                SignatureUtil.getStringFromKey(senderPublicKey) +
                        type.toString() +
                        payloadJson +
                        timestamp);
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
     * Verifies the transaction's signature using the internally stored sender key.
     *
     * @return true if the signature is valid, false otherwise.
     */
    public boolean verifySignature() {
        return SignatureUtil.verify(senderPublicKey, signature, this.hash);
    }

    /**
     * Verifies the transaction's signature using an external sender public key.
     *
     * @param senderPublicKey The public key to verify the transaction with.
     * @return true if the signature is valid, false otherwise.
     */
    public boolean verifySignature(PublicKey senderPublicKey) {
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

    public List<DNSModel> getPayload() {
        return payload;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public byte[] getSignature() {
        return signature;
    }

    public static void publish(Transaction transaction) {
        Message message;
        try {
            message = new Message(
                    MessageType.TRANSACTION_PUBLISH,
                    NetworkUtility.getLocalIpAddress(),
                    DBUtil.getInstance().getPublicKey(),
                    ConversionUtil.toJson(transaction));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ConsolePrinter.printInfo("[Transaction] Sending transaction ");
        NetworkManager.broadcast(ConversionUtil.toJson(message),
                DBUtil.getInstance().getAllNodes(),
                Set.of(Role.ANY));

    }

    @Override
    public String toString() {
        return "Transaction{" +
                "hash='" + hash + '\'' +
                ", senderPublicKey=" + senderPublicKey +
                ", type=" + type +
                ", payload=" + payload +
                ", timestamp=" + timestamp +
                ", signature=" + Arrays.toString(signature) +
                '}';
    }
}
