package org.ddns.node;

import org.ddns.bc.Block;
import org.ddns.bc.SignatureUtil;
import org.ddns.bc.Transaction;
import org.ddns.bc.TransactionType;
import org.ddns.consensus.*;
import org.ddns.constants.ElectionType;
import org.ddns.constants.FileNames;
import org.ddns.constants.Role;
import org.ddns.db.BlockDb;
import org.ddns.db.DBUtil;
import org.ddns.db.DNSDb;
import org.ddns.db.TransactionDb;
import org.ddns.dns.DNSModel;
import org.ddns.dns.DNSPersistence;
import org.ddns.governance.Election;
import org.ddns.net.Message;
import org.ddns.net.MessageHandler;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.TimeUtil;
import org.ddns.web.WebHttpServer;

import java.security.PublicKey;
import java.util.*;

import static org.ddns.Main.testTransaction;

/**
 * Manages the node's view of the network.
 * <p>
 * This class is responsible for two main functions:
 * 1.  Sending requests (e.g., ADD, FETCH) to the Bootstrap node.
 * 2.  Receiving and processing broadcasted updates (ADD, DELETE, PROMOTE)
 * from the Bootstrap node to keep its local node list (in DBUtil) synchronized.
 */
public class NodesManager implements MessageHandler {

    private final Election election;

    /**
     * Registers this manager as a handler with the NetworkManager upon creation.
     *
     * @param networkManager The network manager instance.
     */
    public NodesManager(NetworkManager networkManager, Election election) {
        networkManager.registerHandler(this);
        this.election = election;
        ConsolePrinter.printInfo("[NodesManager] Registered with NetworkManager.");
    }

    @Override
    public void onBroadcastMessage(String message) {
        // no-op (for now)
    }

    /**
     * Handles incoming direct messages, primarily updates from the Bootstrap node.
     *
     * @param message The raw JSON string of the incoming message.
     */
    @Override
    public void onDirectMessage(String message) {
        if (message == null || message.isEmpty()) {
            ConsolePrinter.printWarning("[NodesManager] Received null or empty direct message.");
            return;
        }

        Message incoming;
        try {
            incoming = ConversionUtil.fromJson(message, Message.class);
        } catch (Exception e) {
            ConsolePrinter.printFail("[NodesManager] Failed to parse incoming Message: " + e.getMessage());
            return;
        }

        if (incoming == null || incoming.type == null || incoming.payload == null) {
            ConsolePrinter.printWarning("[NodesManager] Received malformed message (null type or payload).");
            return;
        }

        ConsolePrinter.printInfo("[NodesManager] Received direct message: " + incoming.type);
        String payload = incoming.payload;

        // Route message to the appropriate handler
        switch (incoming.type) {
            case ADD -> resolveAddNode(payload);
            case DELETE -> resolveRemoveNode(payload);
            case PROMOTE -> resolvePromoteNode(payload);
            case FETCH_NODES_RESPONSE -> resolveFetchResponse(payload);
            case SYNC_REQUEST -> resolveSyncRequest(incoming);
            case QUEUE_UPDATE -> resolveQueueUpdate(payload);
            default -> {
//                ConsolePrinter.printInfo("[NodesManager] Ignored message type: " + incoming.type);
            }
        }
    }

    private void resolveQueueUpdate(String payload) {

        Set<QueueNode> queueNodeSet =
                ConversionUtil.jsonToSet(payload, QueueNode.class);

        if (queueNodeSet == null || queueNodeSet.isEmpty()) {
            return;
        }

        List<QueueNode> list = new ArrayList<>(queueNodeSet);

        // Sort by sequence number to guarantee same order everywhere
        list.sort(Comparator.comparingInt(QueueNode::getSno));

        // Replace entire local queue with authoritative state
        CircularQueue.getInstance().resetWith(list);

        ConsolePrinter.printSuccess("[Consensus] Queue updated. New size = " + list.size());
    }


    @Override
    public void onMulticastMessage(String message) {
        // no-op (for now)
    }

    // -------------------------------------------------------------------------
    // RESOLVERS (Handle incoming updates from Bootstrap)
    // -------------------------------------------------------------------------

    /**
     * Processes a broadcasted ADD_NODE message.
     *
     * @param payload The JSON payload containing the NodeConfig to add.
     */
    public void resolveAddNode(String payload) {
        if (payload == null) return;
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(payload, NodeConfig.class);
            if (nodeConfig == null || nodeConfig.getIp() == null || nodeConfig.getPublicKey() == null) {
                ConsolePrinter.printWarning("[NodesManager] Invalid ADD_NODE payload received.");
                return;
            }

            DBUtil.getInstance().addNode(nodeConfig); // This is an upsert
            ConsolePrinter.printSuccess("[NodesManager] Added/Updated node in local DB: " + nodeConfig.getIp());

            // If bootstrap announces a GENESIS node and that happens to be this node, reuse helper
            NodeConfig self = DBUtil.getInstance().getSelfNode();
            if (self != null && SignatureUtil.getStringFromKey(self.getPublicKey()).equals(SignatureUtil.getStringFromKey(nodeConfig.getPublicKey()))) {
                if (nodeConfig.getRole() != null && nodeConfig.getRole().equals(Role.GENESIS) &&
                DBUtil.getInstance().getSelfNode().getRole()!=Role.GENESIS) {
                    setupGenesisNode();
                }
            }

        } catch (Exception e) {
            ConsolePrinter.printFail("[NodesManager] Error resolving ADD_NODE: " + e.getMessage());
        }
    }

    /**
     * Processes a broadcasted DELETE_NODE message.
     *
     * @param payload The JSON payload containing the NodeConfig to remove.
     */
    public void resolveRemoveNode(String payload) {
        if (payload == null) return;
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(payload, NodeConfig.class);
            if (nodeConfig == null || nodeConfig.getPublicKey() == null) {
                ConsolePrinter.printWarning("[NodesManager] Invalid DELETE_NODE payload received.");
                return;
            }

            boolean success = DBUtil.getInstance().deleteNode(nodeConfig.getPublicKey());
            if (success) {
                ConsolePrinter.printSuccess("[NodesManager] Removed node from local DB: " + nodeConfig.getIp());
            } else {
                ConsolePrinter.printWarning("[NodesManager] Failed to remove node (not found?): " + nodeConfig.getIp());
            }

        } catch (Exception e) {
            ConsolePrinter.printFail("[NodesManager] Error resolving DELETE_NODE: " + e.getMessage());
        }
    }

    /**
     * Processes a broadcasted PROMOTE_NODE message.
     *
     * @param payload The JSON payload containing the NodeConfig to promote.
     */
    public void resolvePromoteNode(String payload) {
        if (payload == null) return;
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(payload, NodeConfig.class);
            if (nodeConfig == null || nodeConfig.getPublicKey() == null) {
                ConsolePrinter.printWarning("[NodesManager] Invalid PROMOTE_NODE payload received.");
                return;
            }

            // Update role in DB by public key string
            String pubKeyStr = SignatureUtil.getStringFromKey(nodeConfig.getPublicKey());
            boolean success = DBUtil.getInstance().updateNode(pubKeyStr, Role.LEADER_NODE, null);

            if (success) {
                ConsolePrinter.printSuccess("[NodesManager] Promoted node in local DB: " + nodeConfig.getIp());

                // If the promoted node is this node, run the local setup to ensure role/state consistency
                NodeConfig self = DBUtil.getInstance().getSelfNode();
                if (self != null && SignatureUtil.getStringFromKey(self.getPublicKey()).equals(pubKeyStr)) {
                    ConsolePrinter.printInfo("[NodesManager] Promotion applies to self — configuring local role as LEADER_NODE.");
                    setupLeaderNode();
                }
            } else {
                ConsolePrinter.printWarning("[NodesManager] Failed to promote node (not found?): " + nodeConfig.getIp());
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[NodesManager] Error resolving PROMOTE_NODE: " + e.getMessage());
        }
    }

    /**
     * Processes the response from a FETCH_NODES request.
     *
     * @param payload The JSON payload containing a Set or Array of NodeConfig objects.
     */
    public void resolveFetchResponse(String payload) {
        if (payload == null) {
            ConsolePrinter.printWarning("[NodesManager] FETCH_NODES_RESPONSE payload is null.");
            return;
        }

        try {
            // Try parsing as a Set first
            Set<NodeConfig> nodeConfigSet = ConversionUtil.jsonToSet(payload, NodeConfig.class);

            // Fallback: Bootstrap might send a JSON array
            if (nodeConfigSet == null || nodeConfigSet.isEmpty()) {
                NodeConfig[] arr = ConversionUtil.fromJson(payload, NodeConfig[].class);
                if (arr != null && arr.length > 0) {
                    nodeConfigSet = new HashSet<>(Arrays.asList(arr));
                }
            }

            // At this point nodeConfigSet may be null or empty
            if (nodeConfigSet == null || nodeConfigSet.isEmpty()) {
                // No nodes returned -> this node should be the genesis node
                ConsolePrinter.printInfo("[NodesManager] FETCH_NODES_RESPONSE: no nodes returned. Promoting self to GENESIS.");

                NodeConfig self = DBUtil.getInstance().getSelfNode();

                if (self == null) {
                    ConsolePrinter.printFail("[NodesManager] Self node not configured; cannot promote to GENESIS.");
                    return;
                }

                // Reuse existing method to configure genesis state and persist it
                setupGenesisNode();

                ConsolePrinter.printSuccess("[NodesManager] Node promoted to GENESIS and persisted locally: " + self.getIp());
                return;
            }

            // There are existing nodes in the network: sync them into local DB
            System.out.println(nodeConfigSet);
            DBUtil.getInstance().addNodes(nodeConfigSet);
            ConsolePrinter.printSuccess("[NodesManager] Synced " + nodeConfigSet.size() + " nodes from Bootstrap.");

        } catch (Exception e) {
            ConsolePrinter.printFail("[NodesManager] Error resolving FETCH_NODES_RESPONSE: " + e.getMessage());
        }
    }


    // -------------------------------------------------------------------------
    // REQUEST CREATORS (Send requests to Bootstrap)
    // -------------------------------------------------------------------------

    /**
     * Sends a request to the Bootstrap node to add this node to the network registry.
     *
     * @throws Exception If public key or self-node config is missing.
     */
    public void createAddNodeRequest() throws Exception {
        ConsolePrinter.printInfo("[NodesManager] Sending ADD_NODE request to Bootstrap...");
        sendBootstrapRequest(MessageType.ADD_NODE);
    }

    /**
     * Sends a request to the Bootstrap node to remove this node from the network registry.
     *
     * @throws Exception If public key or self-node config is missing.
     */
    public void createRemoveRequest() throws Exception {
        ConsolePrinter.printInfo("[NodesManager] Sending DELETE_NODE request to Bootstrap...");
        sendBootstrapRequest(MessageType.DELETE_NODE);
    }

    /**
     * Sends a request to the Bootstrap node to promote this node to a Leader.
     *
     * @throws Exception If public key or self-node config is missing.
     */
    public void createPromoteRequest() throws Exception {
        ConsolePrinter.printInfo("[NodesManager] Sending PROMOTE_NODE request to Bootstrap...");
        sendBootstrapRequest(MessageType.PROMOTE_NODE);
    }

    /**
     * Sends a request to the Bootstrap node to get the complete list of all known nodes.
     *
     * @throws Exception If public key or bootstrap IP is missing.
     */
    public void createFetchRequest() throws Exception {
        String bootstrapIp = DBUtil.getInstance().getBootstrapIp();
        if (bootstrapIp == null) {
            ConsolePrinter.printFail("[NodesManager] Cannot createFetchRequest: Bootstrap IP is not set.");
            return;
        }

        PublicKey selfKey = DBUtil.getInstance().getPublicKey();
        String selfIp = NetworkUtility.getLocalIpAddress();

        Message message = new Message(
                MessageType.FETCH_NODES,
                selfIp,
                selfKey,
                ConversionUtil.toJson(Map.of("IP", NetworkUtility.getLocalIpAddress()))
        );

        ConsolePrinter.printInfo("[NodesManager] Sending FETCH_NODES request to Bootstrap at " + bootstrapIp);
        NetworkManager.sendDirectMessage(bootstrapIp, ConversionUtil.toJson(message));
    }

    // -------------------------------------------------------------------------
    // HELPER METHOD
    // -------------------------------------------------------------------------

    /**
     * Helper method to send this node's configuration to the Bootstrap node
     * for ADD, DELETE, and PROMOTE requests.
     *
     * @param type The MessageType of the request.
     * @throws Exception If bootstrap IP, self-node config, or keys are missing.
     */
    private void sendBootstrapRequest(MessageType type) throws Exception {
        String bootstrapIp = DBUtil.getInstance().getBootstrapIp();
        if (bootstrapIp == null) {
            ConsolePrinter.printFail("[NodesManager] Cannot send request: Bootstrap IP is not set.");
            throw new IllegalStateException("Bootstrap IP not found in DBUtil.");
        }

        NodeConfig selfNode = DBUtil.getInstance().getSelfNode();
        if (selfNode == null) {
            ConsolePrinter.printFail("[NodesManager] Cannot send request: Self node config is not set.");
            throw new IllegalStateException("Self node config not found in DBUtil.");
        }

        Message message = new Message(
                type,
                selfNode.getIp(), // Use IP from self-node config
                selfNode.getPublicKey(), // Use PublicKey from self-node config
                ConversionUtil.toJson(selfNode) // Payload is our own NodeConfig
        );

        NetworkManager.sendDirectMessage(bootstrapIp, ConversionUtil.toJson(message));
    }
//    This method send a request to genesis node to send sync data

    public void createSyncRequest() {
        Set<NodeConfig> nodeConfigSet = DBUtil.getInstance().getAllNodes();
        NodeConfig genesisNode = null;
        System.out.println(nodeConfigSet);
        for (NodeConfig nodeConfig : nodeConfigSet) {
            if (nodeConfig.getRole().equals(Role.GENESIS)) {
                genesisNode = nodeConfig;
                break;
            }
        }

        try {
            Message message = new Message(
                    MessageType.SYNC_REQUEST,
                    NetworkUtility.getLocalIpAddress(),
                    DBUtil.getInstance().getPublicKey(),
                    ""
            );
            if (genesisNode != null)
                NetworkManager.sendDirectMessage(genesisNode.getIp(), ConversionUtil.toJson(message));
            else {
                ConsolePrinter.printFail("[Node Manager] No genesis node found");
                return;
            }
            ConsolePrinter.printInfo("[Node Manager] Sync request is sent to Genesis node");

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void resolveSyncRequest(Message message) {
        String requestNodeIp = message.senderIp;
//        share current db snapshot
        ConsolePrinter.printInfo("[Node Manager] Sending db snapshot to "+requestNodeIp);
        NetworkManager.sendFile(
                requestNodeIp,
                BlockDb.getInstance().exportSnapshot()
        );
    }

    public void resolveSyncResponse(String payload) {
//        TODO Network manager has a method to receive the files.
//        TODO access that file and create sql insert statements and insert them into respective dbs with correct time stamp
//
    }

    public void setupGenesisNode(){
        NodeConfig nodeConfig = DBUtil.getInstance().getSelfNode();

        nodeConfig.setRole(Role.GENESIS);
        DBUtil.getInstance().saveRole(Role.GENESIS);
        DBUtil.getInstance().setSelfNode(nodeConfig);
        DBUtil.getInstance().addNode(nodeConfig);
        TimeUtil.waitForSeconds(1);
        try{
            createAddNodeRequest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ConsensusSystem.start();
        NodeDNSService.configure("0.0.0.0", "example.com.", 64);
        NodeDNSService.start();

        try{
            WebHttpServer.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


    }

    public void setupNormalNode(){
        NodeConfig nodeConfig = DBUtil.getInstance().getSelfNode();
        nodeConfig.setRole(Role.NORMAL_NODE);
        DBUtil.getInstance().saveRole(Role.NORMAL_NODE);
        DBUtil.getInstance().setSelfNode(nodeConfig);
        DBUtil.getInstance().addNode(nodeConfig);
        TimeUtil.waitForSeconds(1);
        try{
            createAddNodeRequest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ConsensusSystem.start();
        NodeDNSService.configure("0.0.0.0", "example.com.", 64);
        NodeDNSService.start();
        try{
            WebHttpServer.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
    public void setupLeaderNode(){
        NodeConfig nodeConfig = DBUtil.getInstance().getSelfNode();
        nodeConfig.setRole(Role.LEADER_NODE);

        DBUtil.getInstance().saveRole(Role.LEADER_NODE);

        DBUtil.getInstance().setSelfNode(nodeConfig);
        // Ensure the nodes table contains this genesis node
        DBUtil.getInstance().addNode(nodeConfig);
        TimeUtil.waitForSeconds(1);
        try{
            createAddNodeRequest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ConsensusSystem.start();
        NodeDNSService.configure("0.0.0.0", "example.com.", 64);
        NodeDNSService.start();
        try{
            WebHttpServer.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }


    public static void sync(){
        List<String> insertStatements = BlockDb.getInstance().
                extractInsertStatementsFromDbFile(FileNames.BLOCK_DB_TEMP);
        for (String stmt: insertStatements){
            BlockDb.getInstance().executeInsertSQL(stmt);
        }
        applyBlock(false);
    }
    public static synchronized void applyBlock(boolean onlyLast) {

        DNSDb dnsDb = DNSDb.getInstance();

        if (!onlyLast) {


            ConsolePrinter.printInfo("[StateApplier] Rebuilding DNS state from blockchain...");

            // 1. Clear current state
            dnsDb.truncateDatabase(true);

            // 2. Read blocks in correct order
            List<Block> blocks = BlockDb.getInstance().readAllBlocksOrdered();

            for (Block block : blocks) {
                applySingleBlock(block, dnsDb);
            }

            ConsolePrinter.printSuccess("[StateApplier] Full state rebuild completed. Blocks=" + blocks.size());

        } else {
            // ⚡ APPLY ONLY LAST BLOCK

            String lastBlockHash = BlockDb.getInstance().getLatestBlockHash();
            if (lastBlockHash == null || lastBlockHash.equals("0")) return;

            Block lastBlock = BlockDb.getInstance().readBlockByHash(lastBlockHash);
            if (lastBlock == null) return;

            ConsolePrinter.printInfo("[StateApplier] Applying last block: " + lastBlock.getHash());

            applySingleBlock(lastBlock, dnsDb);
        }
    }
    private static void applySingleBlock(Block block, DNSPersistence persistence) {
        if (block == null || block.getTransactions() == null) return;

        for (Transaction transaction : block.getTransactions()) {
            if (transaction.getPayload() == null) continue;

            for (DNSModel dnsModel : transaction.getPayload()) {

                boolean ok = false;

                switch (transaction.getType()) {

                    case REGISTER -> {
                        ok = persistence.addRecord(dnsModel);
                        if (ok) {
                            ConsolePrinter.printSuccess("[STATE] REGISTER applied: " + dnsModel.getName());
                        } else {
                            ConsolePrinter.printFail("[STATE] REGISTER failed (duplicate or invalid): " + dnsModel.getName());
                        }
                    }

                    case UPDATE_RECORDS -> {
                        ok = persistence.updateRecord(dnsModel);
                        if (ok) {
                            ConsolePrinter.printSuccess("[STATE] UPDATE applied: " + dnsModel.getName());
                        } else {
                            ConsolePrinter.printFail("[STATE] UPDATE failed: " + dnsModel.getName());
                        }
                    }

                    case DELETE_RECORDS -> {
                        ok = persistence.deleteRecord(
                                dnsModel.getName(),
                                dnsModel.getType(),
                                dnsModel.getRdata()
                        );
                        if (ok) {
                            ConsolePrinter.printSuccess("[STATE] DELETE applied: " + dnsModel.getName());
                        } else {
                            ConsolePrinter.printFail("[STATE] DELETE failed (not found): " + dnsModel.getName());
                        }
                    }
                }

            }
        }
    }




}
