package org.ddns.bc;

import java.security.PublicKey;
import java.util.*;

/**
 * Manages the entire dDNS blockchain, including the chain of blocks,
 * the current DNS state, pending transactions, and the consensus mechanism.
 */
public class Blockchain {

    // The chain of blocks. For a pruning node, this might only store block headers.
    private final List<Block> chain;
    // --- NEW: Reverse index for IP-to-domain lookups ---
    private final Map<String, String> ipToDomainIndex;
    // The current state of all DNS records. This CANNOT be pruned.
    // Maps a domain name (String) to its current DnsRecord object.
    private final Map<String, DnsRecord> dnsState;

    // A pool of transactions waiting to be included in the next block.
    private final List<Transaction> pendingTransactions;

    // --- Consensus Fields ---
    private final List<PublicKey> leaderKeys;
    private int currentLeaderIndex;

    /**
     * Constructor for the Blockchain.
     * @param initialLeaders A list of public keys for the initial set of leader nodes.
     */
    public Blockchain( List<PublicKey> initialLeaders) {
        this.ipToDomainIndex = new HashMap<>();
        this.chain = new ArrayList<>();
        this.dnsState = new HashMap<>();
        this.pendingTransactions = new ArrayList<>();
        this.leaderKeys = new ArrayList<>(initialLeaders);
        this.currentLeaderIndex = 0;

        // Create the Genesis Block
        Block genesisBlock = new Block("0", Collections.emptyList());
        this.chain.add(genesisBlock);
    }

    /**
     * Adds a new, validated transaction to the pending pool.
     * @param tx The transaction to add.
     * @return true if the transaction is valid and added, false otherwise.
     */
    public boolean addTransaction(Transaction tx) {
        if (tx == null || !tx.verifySignature()) {
            System.err.println("Transaction signature failed to verify. Discarding.");
            return false;
        }

        // Add more validation logic based on the current state here. For example:
        // - For REGISTER: check if dnsState.containsKey(tx.getDomainName())
        // - For TRANSFER: check if tx.getSenderPublicKey() is the current owner in dnsState

        pendingTransactions.add(tx);
        return true;
    }

    /**
     * Creates a new block with pending transactions, but only if it's the specified
     * leader's turn according to the round-robin schedule.
     * @param leaderKey The public key of the leader attempting to create the block.
     * @return The newly created Block, or null if it's not the leader's turn.
     */
    public Block createBlock(PublicKey leaderKey) {
        if (!isLeaderTurn(leaderKey)) {
            System.err.println("Block creation failed: Not the turn of leader " + SignatureUtil.getStringFromKey(leaderKey));
            return null;
        }

        Block newBlock = new Block(getLatestBlock().getHash(), new ArrayList<>(pendingTransactions));
        chain.add(newBlock);

        // Process the transactions to update the live DNS state
        processTransactionsInBlock(newBlock);

        pendingTransactions.clear();
        advanceToNextLeader();

        System.out.println("Block successfully created by leader " + (currentLeaderIndex == 0 ? leaderKeys.size() - 1 : currentLeaderIndex - 1));
        return newBlock;
    }

    /**
     * Processes all transactions within a newly confirmed block and updates the live dnsState.
     * This is the core of the state machine.
     */
    // --- MODIFIED: processTransactionsInBlock now updates both indexes ---
    public void processTransactionsInBlock(Block block) {
        for (Transaction tx : block.getTransactions()) {
            DnsRecord record;
            Map<String, String> payload = tx.getPayload();

            switch (tx.getType()) {
                case REGISTER:
                    long expiry = System.currentTimeMillis() + Long.parseLong(payload.getOrDefault("ttl", "31536000000")); // Default 1 year
                    DnsRecord newRecord = new DnsRecord(
                            tx.getDomainName(), tx.getSenderPublicKey(), payload.get("ip"), expiry
                    );
                    dnsState.put(tx.getDomainName(), newRecord);
                    ipToDomainIndex.put(payload.get("ip"), tx.getDomainName());
                    break;

                case UPDATE_RECORDS:
                    record = dnsState.get(tx.getDomainName());
                    if (record != null && record.getOwner().equals(tx.getSenderPublicKey())) {
                        String oldIp = record.getIpAddress();
                        String newIp = payload.get("newIp");
                        ipToDomainIndex.remove(oldIp);
                        record.setIpAddress(newIp);
                        ipToDomainIndex.put(newIp, record.getDomainName());
                    }
                    break;

                case DELETE_RECORDS:
                    record = dnsState.get(tx.getDomainName());
                    if (record != null && record.getOwner().equals(tx.getSenderPublicKey())) {
                        dnsState.remove(tx.getDomainName());
                        ipToDomainIndex.remove(record.getIpAddress());
                    }
                    break;

                case TRANSFER_OWNERSHIP:
                    // ... implementation would go here ...
                    break;

                case RENEW:
                    // ... implementation would go here ...
                    break;

                // ... other cases
            }
        }
    }

    /**
     * Validates the integrity of the blockchain's headers.
     * This method can run on a pruned node as it only needs block headers.
     * @return true if all hashes and links are correct, false otherwise.
     */
    public boolean isChainValid() {
        for (int i = 1; i < chain.size(); i++) {
            Block currentBlock = chain.get(i);
            Block previousBlock = chain.get(i - 1);

            // 1. Check if the block's stored hash is correct
            if (!currentBlock.getHash().equals(currentBlock.calculateHash())) {
                System.err.println("Block #" + i + " hash is invalid.");
                return false;
            }

            // 2. Check if it's correctly linked to the previous block
            if (!currentBlock.getPreviousHash().equals(previousBlock.getHash())) {
                System.err.println("Chain is broken at Block #" + i);
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a domain name by looking it up in the current state map.
     * This is the primary "read" operation for the DNS.
     * @param domainName The domain to look up.
     * @return The DnsRecord if found, otherwise null.
     */
    public DnsRecord resolveDomain(String domainName) {
        return dnsState.get(domainName);
    }

    // --- Consensus Helper Methods ---

    public boolean isLeaderTurn(PublicKey key) {
        if (leaderKeys.isEmpty()) return false;
        return leaderKeys.get(currentLeaderIndex).equals(key);
    }

    public void advanceToNextLeader() {
        currentLeaderIndex = (currentLeaderIndex + 1) % leaderKeys.size();
    }

    public Block getLatestBlock() {
        return chain.get(chain.size() - 1);
    }

    public List<Block> getChain() {
        return chain;
    }
    // Add this method inside your Blockchain.java class

    public List<PublicKey> getLeaderKeys() {
        return new ArrayList<>(this.leaderKeys);
    }
    /**
     * Finds a domain name by its associated IP address.
     * @param ip The IP address to search for.
     * @return The domain name if found, otherwise null.
     */
    public String findDomainByIp(String ip) {
        return ipToDomainIndex.get(ip);
    }

    public List<Transaction> getPendingTransactions() {
            return pendingTransactions;
    }
}