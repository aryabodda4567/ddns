package org.ddns.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.ddns.bc.Block;
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
import org.ddns.dns.*;
import org.ddns.governance.Election;
import org.ddns.net.Message;
import org.ddns.net.MessageHandler;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.TimeUtil;

import javax.sound.sampled.Port;
import java.security.PublicKey;
import java.util.*;

/**
 * Manages the node's view of the network.
 * <p>
 * This class is responsible for two main functions:
 * 1. Sending requests (e.g., ADD, FETCH) to the Bootstrap node.
 * 2. Receiving and processing broadcasted updates (ADD, DELETE, PROMOTE)
 * from the Bootstrap node to keep its local node list (in DBUtil) synchronized.
 */
public class NodesManager implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(NodesManager.class);

    private final Election election;
    private final static int PORT = 53;

    /**
     * Registers this manager as a handler with the NetworkManager upon creation.
     *
     * @param networkManager The network manager instance.
     */
    public NodesManager(NetworkManager networkManager, Election election) {
        networkManager.registerHandler(this);
        this.election = election;
        log.info("[NodesManager] Registered with NetworkManager.");
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
            log.warn("[NodesManager] Received null or empty direct message.");
            return;
        }

        Message incoming;
        try {
            incoming = ConversionUtil.fromJson(message, Message.class);
        } catch (Exception e) {
            log.error("[NodesManager] Failed to parse incoming Message: " + e.getMessage());
            return;
        }

        if (incoming == null || incoming.type == null || incoming.payload == null) {
            log.warn("[NodesManager] Received malformed message (null type or payload).");
            return;
        }

        log.info("[NodesManager] Received direct message: " + incoming.type);
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
                // log.info("[NodesManager] Ignored message type: " +
                // incoming.type);
            }
        }
    }

    private void resolveQueueUpdate(String payload) {

        Set<QueueNode> queueNodeSet = ConversionUtil.jsonToSet(payload, QueueNode.class);

        if (queueNodeSet == null || queueNodeSet.isEmpty()) {
            return;
        }

        List<QueueNode> list = new ArrayList<>(queueNodeSet);

        // Sort by sequence number to guarantee same order everywhere
        list.sort(Comparator.comparingInt(QueueNode::getSno));

        // Replace entire local queue with authoritative state
        CircularQueue.getInstance().resetWith(list);

        log.info("[Consensus] Queue updated. New size = " + list.size());
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
        if (payload == null)
            return;
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(payload, NodeConfig.class);
            if (nodeConfig == null || nodeConfig.getIp() == null || nodeConfig.getPublicKey() == null) {
                log.warn("[NodesManager] Invalid ADD_NODE payload received.");
                return;
            }

            DBUtil.getInstance().addNode(nodeConfig); // This is an upsert
            log.info("[NodesManager] Added/Updated node in local DB: " + nodeConfig.getIp());

        } catch (Exception e) {
            log.error("[NodesManager] Error resolving ADD_NODE: " + e.getMessage());
        }
    }

    /**
     * Processes a broadcasted DELETE_NODE message.
     *
     * @param payload The JSON payload containing the NodeConfig to remove.
     */
    public void resolveRemoveNode(String payload) {
        if (payload == null)
            return;
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(payload, NodeConfig.class);
            if (nodeConfig == null || nodeConfig.getPublicKey() == null) {
                log.warn("[NodesManager] Invalid DELETE_NODE payload received.");
                return;
            }

            boolean success = DBUtil.getInstance().deleteNode(nodeConfig.getPublicKey());
            if (success) {
                log.info("[NodesManager] Removed node from local DB: " + nodeConfig.getIp());
            } else {
                log.warn("[NodesManager] Failed to remove node (not found?): " + nodeConfig.getIp());
            }

        } catch (Exception e) {
            log.error("[NodesManager] Error resolving DELETE_NODE: " + e.getMessage());
        }
    }

    /**
     * Processes a broadcasted PROMOTE_NODE message.
     *
     * @param payload The JSON payload containing the NodeConfig to promote.
     */
    public void resolvePromoteNode(String payload) {
        if (payload == null)
            return;
        try {
            // No role promotions in egalitarian system - all nodes equal
            log.info("[NodesManager] Node promotion message ignored (egalitarian system)");
        } catch (Exception e) {
            log.error("[NodesManager] Error resolving PROMOTE_NODE: " + e.getMessage());
        }
    }

    /**
     * Processes the response from a FETCH_NODES request.
     *
     * @param payload The JSON payload containing a Set or Array of NodeConfig
     *                objects.
     */
    public void resolveFetchResponse(String payload) {
        if (payload == null) {
            log.warn("[NodesManager] FETCH_NODES_RESPONSE payload is null.");
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
                // No nodes returned -> first node joins as regular participant
                log.info(
                        "[NodesManager] FETCH_NODES_RESPONSE: no nodes returned. Starting as first node.");

                NodeConfig self = DBUtil.getInstance().getSelfNode();

                if (self == null) {
                    log.error("[NodesManager] Self node not configured; cannot start node.");
                    return;
                }

                setupEqualNode();
                log.info("[NodesManager] Node started and persisted locally: " + self.getIp());
                return;
            }

            // There are existing nodes in the network: sync them into local DB
            System.out.println(nodeConfigSet);
            DBUtil.getInstance().addNodes(nodeConfigSet);
            log.info("[NodesManager] Synced " + nodeConfigSet.size() + " nodes from Bootstrap.");

        } catch (Exception e) {
            log.error("[NodesManager] Error resolving FETCH_NODES_RESPONSE: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // REQUEST CREATORS (Send requests to Bootstrap)
    // -------------------------------------------------------------------------

    /**
     * Sends a request to the Bootstrap node to add this node to the network
     * registry.
     *
     * @throws Exception If public key or self-node config is missing.
     */
    public static void createAddNodeRequest() throws Exception {
        log.info("[NodesManager] Sending ADD_NODE request to Bootstrap...");
        sendBootstrapRequest(MessageType.ADD_NODE);
    }

    /**
     * Sends a request to the Bootstrap node to remove this node from the network
     * registry.
     *
     * @throws Exception If public key or self-node config is missing.
     */
    public void createRemoveRequest() throws Exception {
        log.info("[NodesManager] Sending DELETE_NODE request to Bootstrap...");
        sendBootstrapRequest(MessageType.DELETE_NODE);
    }

    /**
     * Sends a request to the Bootstrap node to promote this node to a Leader.
     *
     * @throws Exception If public key or self-node config is missing.
     */
    public static void createPromoteRequest() throws Exception {
        log.info("[NodesManager] Sending PROMOTE_NODE request to Bootstrap...");
        sendBootstrapRequest(MessageType.PROMOTE_NODE);
    }

    /**
     * Sends a request to the Bootstrap node to get the complete list of all known
     * nodes.
     *
     * @throws Exception If public key or bootstrap IP is missing.
     */
    public static void createFetchRequest() throws Exception {
        String bootstrapIp = DBUtil.getInstance().getBootstrapIp();
        if (bootstrapIp == null) {
            log.error("[NodesManager] Cannot createFetchRequest: Bootstrap IP is not set.");
            return;
        }

        PublicKey selfKey = DBUtil.getInstance().getPublicKey();
        String selfIp = NetworkUtility.getLocalIpAddress();

        Message message = new Message(
                MessageType.FETCH_NODES,
                selfIp,
                selfKey,
                ConversionUtil.toJson(Map.of("IP", NetworkUtility.getLocalIpAddress())));

        log.info("[NodesManager] Sending FETCH_NODES request to Bootstrap at " + bootstrapIp);
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
    private static void sendBootstrapRequest(MessageType type) throws Exception {
        String bootstrapIp = DBUtil.getInstance().getBootstrapIp();
        if (bootstrapIp == null) {
            log.error("[NodesManager] Cannot send request: Bootstrap IP is not set.");
            throw new IllegalStateException("Bootstrap IP not found in DBUtil.");
        }

        NodeConfig selfNode = DBUtil.getInstance().getSelfNode();
        if (selfNode == null) {
            log.error("[NodesManager] Cannot send request: Self node config is not set.");
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
    // This method sends a request to any peer node to send sync data

    public static void createSyncRequest() {
        Set<NodeConfig> nodeConfigSet = DBUtil.getInstance().getAllNodes();
        NodeConfig targetNode = null;
        for (NodeConfig nodeConfig : nodeConfigSet) {
            if (nodeConfig == null) continue;
            NodeConfig self = DBUtil.getInstance().getSelfNode();
            if (self != null && nodeConfig.getIp().equals(self.getIp())) continue;
            targetNode = nodeConfig;
            break;
        }

        try {
            Message message = new Message(
                    MessageType.SYNC_REQUEST,
                    NetworkUtility.getLocalIpAddress(),
                    DBUtil.getInstance().getPublicKey(),
                    "");
            if (targetNode != null) {
                NetworkManager.sendDirectMessage(targetNode.getIp(), ConversionUtil.toJson(message));
            } else {
                log.error("[Node Manager] No peer node found for sync");
                return;
            }
            log.info("[Node Manager] Sync request sent to peer " + targetNode.getIp());

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void resolveSyncRequest(Message message) {
        String requestNodeIp = message.senderIp;
        // share current db snapshot
        log.info("[Node Manager] Sending db snapshot to " + requestNodeIp);
        NetworkManager.sendFile(
                requestNodeIp,
                BlockDb.getInstance().exportSnapshot());
    }

    public void resolveSyncResponse(String payload) {
        // TODO Network manager has a method to receive the files.
        // TODO access that file and create sql insert statements and insert them into
        // respective dbs with correct time stamp
        //
    }

    public void setupGenesisNode() throws Exception {
        // Legacy entry point now delegates to egalitarian setup
        setupEqualNode();
    }

    /**
     * Setup a regular node (all nodes are equal in egalitarian system)
     */
    public static void setupEqualNode() throws Exception {
        NodeConfig nodeConfig = DBUtil.getInstance().getSelfNode();
        if (nodeConfig.getRole() == null) {
            nodeConfig.setRole(Role.NONE);
        }
        DBUtil.getInstance().saveRole(Role.NONE);
        DBUtil.getInstance().setSelfNode(nodeConfig);
        DBUtil.getInstance().addNode(nodeConfig);
        TimeUtil.waitForSeconds(1);
        try {
            createAddNodeRequest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ConsensusSystem.start();
        DNSServer.start();
        DNSService.start(PORT);
        try {

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public static void sync() {
        List<String> insertStatements = BlockDb.getInstance()
                .extractInsertStatementsFromDbFile(FileNames.BLOCK_DB_TEMP);
        for (String stmt : insertStatements) {
            BlockDb.getInstance().executeInsertSQL(stmt);
        }
        applyBlock(false);
    }

    public static synchronized void applyBlock(boolean onlyLast) {

        DNSDb dnsDb = DNSDb.getInstance();

        if (!onlyLast) {

            log.info("[StateApplier] Rebuilding DNS state from blockchain...");

            // 1. Clear current state
            dnsDb.truncateDatabase(true);

            // 2. Read blocks in correct order
            List<Block> blocks = BlockDb.getInstance().readAllBlocksOrdered();

            for (Block block : blocks) {
                applySingleBlock(block, dnsDb);
            }

            log.info("[StateApplier] Full state rebuild completed. Blocks=" + blocks.size());

        } else {
            // ⚡ APPLY ONLY LAST BLOCK

            String lastBlockHash = BlockDb.getInstance().getLatestBlockHash();
            if (lastBlockHash == null || lastBlockHash.equals("0"))
                return;

            Block lastBlock = BlockDb.getInstance().readBlockByHash(lastBlockHash);
            if (lastBlock == null)
                return;

            log.info("[StateApplier] Applying last block: " + lastBlock.getHash());

            applySingleBlock(lastBlock, dnsDb);
        }
    }

    private static void applySingleBlock(Block block, DNSPersistence persistence) {
        if (block == null || block.getTransactions() == null)
            return;

        for (Transaction transaction : block.getTransactions()) {
            if (transaction.getPayload() == null)
                continue;

            for (DNSModel dnsModel : transaction.getPayload()) {

                boolean ok = false;

                switch (transaction.getType()) {

                    case REGISTER -> {
                        ok = persistence.addRecord(dnsModel);
                        if (ok)
                            log.info("[STATE] REGISTER applied: " + dnsModel.getName());
                        else
                            log.error("[STATE] REGISTER failed: " + dnsModel.getName());
                    }

                    case UPDATE_RECORDS -> {
                        ok = persistence.updateRecord(dnsModel);
                        if (ok)
                            log.info("[STATE] UPDATE applied: " + dnsModel.getName());
                        else
                            log.error("[STATE] UPDATE failed: " + dnsModel.getName());
                    }

                    case DELETE_RECORDS -> {
                        ok = persistence.deleteRecord(
                                dnsModel.getName(),
                                dnsModel.getType(),
                                dnsModel.getRdata());
                        if (ok)
                            log.info("[STATE] DELETE applied: " + dnsModel.getName());
                        else
                            log.error("[STATE] DELETE failed: " + dnsModel.getName());
                    }
                }
            }
        }
    }

}
