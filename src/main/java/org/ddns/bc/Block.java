package org.ddns.bc;

import org.ddns.constants.Role;
import org.ddns.db.DBUtil;
import org.ddns.net.Message;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;

import java.util.List;
import java.util.Set;

/**
 * Represents a block in a pruning-enabled blockchain.
 * The block's hash is calculated using the Merkle Root of its transactions,
 * which allows the transaction data itself to be pruned later without
 * invalidating the chain's integrity.
 */
public class Block {

    private String hash;
    private String previousHash;
    private String merkleRoot; // The root hash of all transactions in this block
    private long timestamp;
    private List<Transaction> transactions; // Can be pruned by non-archival nodes

    /**
     * Constructor for a new Block.
     *
     * @param previousHash The hash of the previous block in the chain.
     * @param transactions The list of transactions to be included.
     */
    public Block(String previousHash, List<Transaction> transactions, long timeStamp) {
        this.previousHash = previousHash;
        this.transactions = transactions;
        this.timestamp = timeStamp;
        this.merkleRoot = MerkleTree.getMerkleRoot(transactions); // Generate Merkle Root
        this.hash = calculateHash(); // Calculate the block's final hash
    }

    /**
     * Calculates the block's hash based on its header information.
     * Crucially, this does NOT depend on the full transaction list, only the Merkle
     * Root.
     *
     * @return The SHA-256 hash of the block header.
     */
    public String calculateHash() {
        return SignatureUtil.applySha256(
                previousHash +
                        merkleRoot +
                        timestamp);
    }

    // --- Getters ---
    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    public String getMerkleRoot() {
        return merkleRoot;
    }

    public void setMerkleRoot(String merkleRoot) {
        this.merkleRoot = merkleRoot;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public void setTransactions(List<Transaction> transactions) {
        this.transactions = transactions;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public static void publish(Block block) {
        Message message;
        try {
            message = new Message(
                    MessageType.BLOCK_PUBLISH,
                    NetworkUtility.getLocalIpAddress(),
                    DBUtil.getInstance().getPublicKey(),
                    ConversionUtil.toJson(block));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        NetworkManager.broadcast(ConversionUtil.toJson(message),
                DBUtil.getInstance().getAllNodes(),
                Set.of(Role.ANY));

    }

    @Override
    public String toString() {
        return "Block{" +
                "hash='" + hash + '\'' +
                ", previousHash='" + previousHash + '\'' +
                ", merkleRoot='" + merkleRoot + '\'' +
                ", timestamp=" + timestamp +
                ", transactions=" + transactions +
                '}';
    }
}