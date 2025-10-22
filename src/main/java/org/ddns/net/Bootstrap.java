package org.ddns.net;

import org.ddns.chain.Role;
import org.ddns.db.DBUtil; // Import DBUtil
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
// Removed: import org.ddns.util.PersistentStorage;

import java.security.PublicKey;
import java.util.Map;
import java.util.Set;

/**
 * Manages bootstrap node operations for the dDNS network using DBUtil for persistence.
 */
public class Bootstrap implements MessageHandler {

    private static Bootstrap INSTANCE = null;
    private NetworkManager networkManager;
    // --- Use DBUtil for persistence ---
    private final DBUtil dbUtil;

    private static final Set<MessageType> allowedTypes;

    static {
        allowedTypes = Set.of(MessageType.BOOTSTRAP_REQUEST,
                MessageType.BOOTSTRAP_RESPONSE,
                MessageType.ADD_NODE_TO_BOOTSTRAP,
                MessageType.PROMOTE_TO_LEADER
        );
    }

    private NodeBootstrapListener listener;

    public interface NodeBootstrapListener {
        void onBootstrapNodesReceived(Set<NodeConfig> nodes);
    }

    // --- Modified Constructors ---
    private Bootstrap(String bootstrapNodeIp) {
        this.dbUtil = DBUtil.getInstance(); // Get DB instance
        if (bootstrapNodeIp == null || bootstrapNodeIp.isEmpty()) {
            ConsolePrinter.printFail("[Bootstrap] Invalid bootstrap node IP.");
            throw new IllegalArgumentException("Bootstrap node IP cannot be null or empty.");
        }
        // Save bootstrap IP using DBUtil
        dbUtil.saveBootstrapIp(bootstrapNodeIp);
        ConsolePrinter.printInfo("[Bootstrap] Bootstrap node initialized with IP: " + bootstrapNodeIp);
        INSTANCE = this; // Set instance *after* initialization
    }

    // Public constructor now just gets the DB instance
    public Bootstrap() {
        this.dbUtil = DBUtil.getInstance();
    }

    // --- Modified Singleton Access ---
    public static synchronized void initialize(String bootstrapNodeIp) {
        // Initialize only creates the instance if needed
        if (INSTANCE == null) {
            INSTANCE = new Bootstrap(bootstrapNodeIp);
        }
    }

    public static synchronized Bootstrap getInstance() {
        if (INSTANCE == null) {
            // If not initialized with an IP first, create a standard instance
            INSTANCE = new Bootstrap();
        }
        return INSTANCE;
    }

    // --- Node Requests / Responses (Using DBUtil) ---

    public void createNodesRequest() {
        try {
            // Get bootstrap IP using DBUtil
            String bootstrapNodeIp = dbUtil.getBootstrapIp();
            if (bootstrapNodeIp == null || bootstrapNodeIp.isEmpty()) {
                ConsolePrinter.printFail("[Bootstrap] Bootstrap node IP not found in DB.");
                return;
            }

            // Get public key using DBUtil
            PublicKey publicKey = dbUtil.getPublicKey();
            String requestedNodeIp = NetworkUtility.getLocalIpAddress();

            if (publicKey == null) {
                ConsolePrinter.printFail("[Bootstrap] Public key not found in DB.");
                return;
            }

            Message message = new Message(
                    MessageType.BOOTSTRAP_REQUEST,
                    requestedNodeIp,
                    publicKey,
                    null // No payload
            );

            boolean result = NetworkManager.sendDirectMessage(bootstrapNodeIp, ConversionUtil.toJson(message));
            // ... (rest of logging remains the same) ...
        } catch (Exception e) {
            ConsolePrinter.printFail("[Bootstrap] Error creating node request: " + e.getMessage());
        }
    }

    public void resolveNodesRequest(Message message) {
        if (message == null || message.senderIp == null) {
            // ... (logging remains the same) ...
            return;
        }

        try {
            String requestedNodeIp = message.senderIp;
            // Get node list directly from DBUtil
            Map<String, String> resultPayload = Map.of("NODES", ConversionUtil.toJson(dbUtil.getAllNodes()));

            Message responseMessage = new Message(
                    MessageType.BOOTSTRAP_RESPONSE,
                    NetworkUtility.getLocalIpAddress(),
                    dbUtil.getPublicKey(), // Get own public key from DB
                    ConversionUtil.toJson(resultPayload)
            );

            boolean sent = NetworkManager.sendDirectMessage(requestedNodeIp, ConversionUtil.toJson(responseMessage));
            // ... (rest of logging remains the same) ...
        } catch (Exception e) {
            ConsolePrinter.printFail("[Bootstrap] Error resolving node request: " + e.getMessage());
        }
    }

    public void resolveNodesResponse(Map<String, String> requestPayloadMap) {
        if (requestPayloadMap == null || !requestPayloadMap.containsKey("NODES")) {
            // ... (logging remains the same) ...
            return;
        }

        String nodesJsonString = requestPayloadMap.get("NODES");
        Set<NodeConfig> nodes = ConversionUtil.jsonToSet(nodesJsonString, NodeConfig.class);
        if (nodes == null || nodes.isEmpty()) {
            ConsolePrinter.printInfo("[Bootstrap] No nodes found in response.");
            return;
        }

        // --- Use DBUtil to save the received nodes ---
        dbUtil.addNodes(nodes); // This method handles saving/updating each node
        ConsolePrinter.printSuccess("[Bootstrap] Updated available nodes list in DB. Total nodes: " + nodes.size());

        if (listener != null) {
            listener.onBootstrapNodesReceived(nodes);
        }
    }

    public void createAddNewNodeRequest() {
        try {
            // Get required info using DBUtil
            PublicKey publicKey = dbUtil.getPublicKey();
            String selfIp = NetworkUtility.getLocalIpAddress();
            String bootstrapNodeIp = dbUtil.getBootstrapIp();
            Role role = dbUtil.getRole();

            if (bootstrapNodeIp == null || bootstrapNodeIp.isEmpty()) { /* ... error handling ... */ return; }
            if (publicKey == null) { /* ... error handling ... */ return; }
            if (role == Role.NONE) { /* ... error handling ... */ return; }


            NodeConfig nodeConfig = new NodeConfig(selfIp, role, publicKey);
            Map<String, String> resultMap = Map.of("NODE", ConversionUtil.toJson(nodeConfig));

            Message message = new Message(
                    MessageType.ADD_NODE_TO_BOOTSTRAP,
                    selfIp,
                    publicKey,
                    ConversionUtil.toJson(resultMap)
            );

            // Get current nodes from DB to broadcast to them
            boolean result1 = NetworkManager.broadcast(ConversionUtil.toJson(message), dbUtil.getAllNodes(), Set.of(Role.NORMAL_NODE, Role.LEADER_NODE, Role.GENESIS));
            boolean result2 = NetworkManager.sendDirectMessage(bootstrapNodeIp, ConversionUtil.toJson(message));

            // ... (rest of logging and return remains the same) ...
        } catch (Exception e) {
            ConsolePrinter.printFail("[Bootstrap] Error creating add node request: " + e.getMessage());
        }
    }

    public void createPromoteNodeRequest() {
        try {
            // Get required info using DBUtil
            PublicKey publicKey = dbUtil.getPublicKey();
            String selfIp = NetworkUtility.getLocalIpAddress();
            String bootstrapNodeIp = dbUtil.getBootstrapIp();
            Role role = dbUtil.getRole(); // Should ideally be NORMAL_NODE before promotion

            if (bootstrapNodeIp == null || bootstrapNodeIp.isEmpty()) { /* ... error handling ... */ return; }
            if (publicKey == null) { /* ... error handling ... */ return; }

            // Create NodeConfig representing the PROMOTION request (role might still be current role)
            NodeConfig nodeConfig = new NodeConfig(selfIp, role, publicKey);
            Map<String, String> resultMap = Map.of("NODE", ConversionUtil.toJson(nodeConfig));

            Message message = new Message(
                    MessageType.PROMOTE_TO_LEADER,
                    selfIp,
                    publicKey,
                    ConversionUtil.toJson(resultMap)
            );

            // Get current nodes from DB to broadcast to them
            boolean result1 = NetworkManager.broadcast(ConversionUtil.toJson(message), dbUtil.getAllNodes(), Set.of(Role.NORMAL_NODE, Role.LEADER_NODE, Role.GENESIS));
            boolean result2 = NetworkManager.sendDirectMessage(bootstrapNodeIp, ConversionUtil.toJson(message));

            // ... (rest of logging and return remains the same) ...
        } catch (Exception e) {
            ConsolePrinter.printFail("[Bootstrap] Error creating promote node request: " + e.getMessage());
        }
    }

    // --- Modified resolve methods to use DBUtil ---
    public boolean resolveAddNewNodeRequest(Map<String, String> payloadMap) {
        if (payloadMap == null || !payloadMap.containsKey("NODE")) { /* ... error handling ... */ return false; }

        NodeConfig nodeConfig = ConversionUtil.fromJson(payloadMap.get("NODE"), NodeConfig.class);
        if (nodeConfig == null || nodeConfig.getPublicKey() == null) { // Check PublicKey too
            ConsolePrinter.printFail("[Bootstrap] Failed to deserialize node configuration or missing public key.");
            return false;
        }

        // Use DBUtil to save
        dbUtil.saveOrUpdateNode(nodeConfig);
        ConsolePrinter.printSuccess("[Bootstrap] Node added or updated successfully in DB: " + nodeConfig.getIp());
        return true;
    }

    public void resolvePromoteNodeRequest(Map<String, String> payloadMap) {
        if (payloadMap == null || !payloadMap.containsKey("NODE")) { /* ... error handling ... */ return; }

        NodeConfig receivedConfig = ConversionUtil.fromJson(payloadMap.get("NODE"), NodeConfig.class);
        if (receivedConfig == null || receivedConfig.getPublicKey() == null) { // Check PublicKey too
            ConsolePrinter.printFail("[Bootstrap] Failed to deserialize node configuration for promotion or missing public key.");
            return;
        }

        // Create the updated config with the new Role
        NodeConfig promotedConfig = new NodeConfig(receivedConfig.getIp(), Role.LEADER_NODE, receivedConfig.getPublicKey());

        // Use DBUtil to save the updated config
        dbUtil.saveOrUpdateNode(promotedConfig);
        ConsolePrinter.printSuccess("[Bootstrap] Node promoted successfully in DB: " + promotedConfig.getIp());
    }

    // --- Node management (Using DBUtil) ---

    /** Get all known nodes directly from the database */
    public Set<NodeConfig> getNodes() {
        return dbUtil.getAllNodes();
    }

    /** Save or update a node directly in the database */
    public void saveOrUpdateNode(NodeConfig nodeConfig) {
        // Basic validation before saving
        if (nodeConfig == null || nodeConfig.getIp() == null || nodeConfig.getPublicKey() == null) {
            ConsolePrinter.printWarning("[Bootstrap] Attempted to save/update invalid node configuration.");
            return;
        }
        dbUtil.saveOrUpdateNode(nodeConfig);
        // Logging is now handled inside DBUtil
    }

    // --- MessageHandler Interface Implementation (Mostly unchanged, relies on resolve methods) ---
    @Override
    public void onBroadcastMessage(String message) {
        // Broadcasts are typically for discovery or general announcements,
        // often handled by other modules like NodeJoin or consensus.
        // Bootstrap might listen for ADD_NODE broadcasts if not sent directly.
    }

    @Override
    public void onDirectMessage(String message) {
        try {
            if (message == null || message.isEmpty()) return;
            Message msg = ConversionUtil.fromJson(message, Message.class);
            if (msg == null || msg.type == null) return;
            Map<String, String> payloadMap = msg.payload != null ? ConversionUtil.jsonToMap(msg.payload) : null;

            if (!allowedTypes.contains(msg.type)) return; // Filter unrelated messages

            switch (msg.type) {
                case BOOTSTRAP_REQUEST -> {
                    ConsolePrinter.printInfo("[Bootstrap] Received bootstrap request from " + msg.senderIp);
                    resolveNodesRequest(msg);
                }
                case BOOTSTRAP_RESPONSE -> {
                    ConsolePrinter.printInfo("[Bootstrap] Received bootstrap response from " + msg.senderIp);
                    resolveNodesResponse(payloadMap);
                }
                case ADD_NODE_TO_BOOTSTRAP -> {
                    ConsolePrinter.printInfo("[Bootstrap] Received add-node request from " + msg.senderIp);
                    boolean success = resolveAddNewNodeRequest(payloadMap);
                    // Log success/failure
                }
                case PROMOTE_TO_LEADER -> {
                    ConsolePrinter.printInfo("[Bootstrap] Received promotion request from " + msg.senderIp);
                    resolvePromoteNodeRequest(payloadMap);
                }
                default -> ConsolePrinter.printWarning("[Bootstrap] Unknown direct message type: " + msg.type);
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[Bootstrap] Error processing direct message: " + e.getMessage());
            e.printStackTrace(); // Log stack trace for debugging
        }
    }

    @Override
    public void onMulticastMessage(String message) {
        // Likely not used by Bootstrap directly, maybe by consensus module
    }

    // --- Registration and Listener ---
    public void register(NetworkManager networkManager) {
        this.networkManager = networkManager;
        networkManager.registerHandler(this);
    }

    public void stop() {
        if (this.networkManager != null) {
            // networkManager.stop(); // NetworkManager might be shared, stop it elsewhere
            this.networkManager.unregisterHandler(this);
        }
    }

    public void setListener(NodeBootstrapListener listener) {
        this.listener = listener;
    }
}