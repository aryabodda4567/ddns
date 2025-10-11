package org.ddns.bc;

import java.util.List;

/**
 * Represents a block in a pruning-enabled blockchain.
 * The block's hash is calculated using the Merkle Root of its transactions,
 * which allows the transaction data itself to be pruned later without
 * invalidating the chain's integrity.
 */
public class Block {

    private final String hash;
    private final String previousHash;
    private final String merkleRoot; // The root hash of all transactions in this block
    private final long timestamp;
    private final List<Transaction> transactions; // Can be pruned by non-archival nodes

    /**
     * Constructor for a new Block.
     *
     * @param previousHash The hash of the previous block in the chain.
     * @param transactions The list of transactions to be included.
     */
    public Block(String previousHash, List<Transaction> transactions) {
        this.previousHash = previousHash;
        this.transactions = transactions;
        this.timestamp = System.currentTimeMillis();
        this.merkleRoot = MerkleTree.getMerkleRoot(transactions); // Generate Merkle Root
        this.hash = calculateHash(); // Calculate the block's final hash
    }

    /**
     * Calculates the block's hash based on its header information.
     * Crucially, this does NOT depend on the full transaction list, only the Merkle Root.
     *
     * @return The SHA-256 hash of the block header.
     */
    public String calculateHash() {
        return SignatureUtil.applySha256(
                previousHash +
                        merkleRoot +
                        timestamp
        );
    }

    // --- Getters ---
    public String getHash() {
        return hash;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getMerkleRoot() {
        return merkleRoot;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public long getTimestamp() {
        return timestamp;
    }
}