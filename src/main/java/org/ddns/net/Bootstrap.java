package org.ddns.net;

import org.ddns.chain.Names;
import org.ddns.chain.Role;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.PersistentStorage;

import java.security.PublicKey;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages bootstrap node operations for the dDNS network.
 * <p>
 * Thread-safety:
 * - Reads are non-blocking, leveraging PersistentStorage concurrent reads.
 * - Writes (node addition/update) are synchronized to ensure atomicity.
 */
public class Bootstrap implements MessageHandler {

    private NetworkManager networkManager ;
    // Lock object for write operations
    private final Object NODE_LOCK = new Object();

    private static final Set<MessageType> allowedTypes ;

    static {
        allowedTypes = Set.of(MessageType.BOOTSTRAP_REQUEST,
                MessageType.BOOTSTRAP_RESPONSE,
                MessageType.ADD_NODE_TO_BOOTSTRAP
                );
    }

    private NodeBootstrapListener listener;

    public interface NodeBootstrapListener {
        void onBootstrapNodesReceived(Set<NodeConfig> nodes);
    }



    // Constructors
    public Bootstrap(String bootstrapNodeIp) {
        if (bootstrapNodeIp == null || bootstrapNodeIp.isEmpty()) {
            ConsolePrinter.printFail("[Bootstrap] Invalid bootstrap node IP.");
            throw new IllegalArgumentException("Bootstrap node IP cannot be null or empty.");
        }
        PersistentStorage.put(Names.BOOTSTRAP_NODE_IP, bootstrapNodeIp);
        ConsolePrinter.printInfo("[Bootstrap] Bootstrap node initialized with IP: " + bootstrapNodeIp);

    }

    public Bootstrap() {
//        ConsolePrinter.printInfo("[Bootstrap] Node initialized in standard mode.");

    }

    // --- Node Requests / Responses ---

    public boolean createNodesRequest() {
        try {
            String bootstrapNodeIp = PersistentStorage.getString(Names.BOOTSTRAP_NODE_IP);
            if (bootstrapNodeIp == null || bootstrapNodeIp.isEmpty()) {
                ConsolePrinter.printFail("[Bootstrap] Bootstrap node IP not found.");
                return false;
            }

            PublicKey publicKey = PersistentStorage.getPublicKey();
            String requestedNodeIp = NetworkUtility.getLocalIpAddress();

            Message message = new Message(
                    MessageType.BOOTSTRAP_REQUEST,
                    requestedNodeIp,
                    publicKey,
                    null
            );

            boolean result = NetworkManager.sendDirectMessage(bootstrapNodeIp, ConversionUtil.toJson(message));
            if (result)
                ConsolePrinter.printSuccess("[Bootstrap] Node discovery request sent to: " + bootstrapNodeIp);
            else
                ConsolePrinter.printFail("[Bootstrap] Failed to send node discovery request.");

            return result;
        } catch (Exception e) {
            ConsolePrinter.printFail("[Bootstrap] Error creating node request: " + e.getMessage());
            return false;
        }
    }

    public boolean resolveNodesRequest(Message message) {
        if (message == null || message.senderIp == null) {
            ConsolePrinter.printWarning("[Bootstrap] Invalid message or sender IP in resolveNodesRequest.");
            return false;
        }

        try {
            String requestedNodeIp = message.senderIp;
            Map<String, String> resultPayload = Map.of("NODES", ConversionUtil.toJson(getNodes()));

            Message responseMessage = new Message(
                    MessageType.BOOTSTRAP_RESPONSE,
                    NetworkUtility.getLocalIpAddress(),
                    PersistentStorage.getPublicKey(),
                    ConversionUtil.toJson(resultPayload)
            );

            boolean sent = NetworkManager.sendDirectMessage(requestedNodeIp, ConversionUtil.toJson(responseMessage));
            if (sent)
                ConsolePrinter.printSuccess("[Bootstrap] Node list sent successfully to: " + requestedNodeIp);
            else
                ConsolePrinter.printFail("[Bootstrap] Failed to send node list to: " + requestedNodeIp);

            return sent;
        } catch (Exception e) {
            ConsolePrinter.printFail("[Bootstrap] Error resolving node request: " + e.getMessage());
            return false;
        }
    }

    public void resolveNodesResponse(Map<String, String> requestPayloadMap) {
        if (requestPayloadMap == null || !requestPayloadMap.containsKey("NODES")) {
            ConsolePrinter.printWarning("[Bootstrap] Invalid or missing node data in response.");
            return;
        }

        synchronized (NODE_LOCK) {
            String nodesJsonString = requestPayloadMap.get("NODES");
            Set<NodeConfig> nodes = ConversionUtil.jsonToSet(nodesJsonString, NodeConfig.class);
            if (nodes == null || nodes.isEmpty()) {
                ConsolePrinter.printInfo("[Bootstrap] No nodes found in response.");
                return;
            }

            PersistentStorage.put(Names.AVAILABLE_NODES, nodesJsonString);
            ConsolePrinter.printSuccess("[Bootstrap] Updated available nodes list. Total nodes: " + nodes.size());
            // Notify listener
            if (listener != null) {
                listener.onBootstrapNodesReceived(nodes);
            }
        }
    }

    public boolean createAddNewNodeRequest() {
        try {
            PublicKey publicKey = PersistentStorage.getPublicKey();
            String selfIp = NetworkUtility.getLocalIpAddress();
            String bootstrapNodeIp = PersistentStorage.getString(Names.BOOTSTRAP_NODE_IP);
            String roleStr = PersistentStorage.getString(Names.ROLE);

            if (bootstrapNodeIp == null || bootstrapNodeIp.isEmpty()) {
                ConsolePrinter.printFail("[Bootstrap] Bootstrap IP not found.");
                return false;
            }

            Role role;
            try {
                role = Role.valueOf(roleStr);
            } catch (Exception e) {
                ConsolePrinter.printFail("[Bootstrap] Invalid or missing node role.");
                return false;
            }

            NodeConfig nodeConfig = new NodeConfig(selfIp, role, publicKey);
            Map<String, String> resultMap = Map.of("NODE", ConversionUtil.toJson(nodeConfig));

            Message message = new Message(
                    MessageType.ADD_NODE_TO_BOOTSTRAP,
                    selfIp,
                    publicKey,
                    ConversionUtil.toJson(resultMap)
            );

            boolean result = NetworkManager.sendDirectMessage(bootstrapNodeIp, ConversionUtil.toJson(message));
            if (result)
                ConsolePrinter.printSuccess("[Bootstrap] Node registration request sent successfully.");
            else
                ConsolePrinter.printFail("[Bootstrap] Failed to send registration request.");

            return result;
        } catch (Exception e) {
            ConsolePrinter.printFail("[Bootstrap] Error creating add node request: " + e.getMessage());
            return false;
        }
    }

    public boolean resolveAddNewNodeRequest(Map<String, String> payloadMap) {
        if (payloadMap == null || !payloadMap.containsKey("NODE")) {
            ConsolePrinter.printWarning("[Bootstrap] Invalid payload in add new node request.");
            return false;
        }

        NodeConfig nodeConfig = ConversionUtil.fromJson(payloadMap.get("NODE"), NodeConfig.class);
        if (nodeConfig == null) {
            ConsolePrinter.printFail("[Bootstrap] Failed to deserialize node configuration.");
            return false;
        }

        saveNode(nodeConfig);
        ConsolePrinter.printSuccess("[Bootstrap] Node added successfully: " + nodeConfig.getIp());
        return true;
    }

    // --- Node management ---

    /** Get all known nodes (read-only, thread-safe) */
    public Set<NodeConfig> getNodes() {
        String nodesJsonString = PersistentStorage.getString(Names.AVAILABLE_NODES);
        Set<NodeConfig> nodes = ConversionUtil.jsonToSet(nodesJsonString, NodeConfig.class);
        return nodes != null ? nodes : new HashSet<>();
    }

    /** Save or update a node (atomic operation) */
    public void saveNode(NodeConfig nodeConfig) {
        if (nodeConfig == null || nodeConfig.getIp() == null) {
            ConsolePrinter.printWarning("[Bootstrap] Attempted to save invalid node configuration.");
            return;
        }

        synchronized (NODE_LOCK) {
            Set<NodeConfig> nodeConfigSet = getNodes();
            nodeConfigSet.add(nodeConfig);

            PersistentStorage.put(Names.AVAILABLE_NODES, ConversionUtil.toJson(nodeConfigSet));
            ConsolePrinter.printInfo("[Bootstrap] Node list updated. Total nodes: " + nodeConfigSet.size());
        }
    }

    @Override
    public void onBroadcastMessage(String message) {




    }

    @Override
    public void onDirectMessage(String message) {
        try {
            if (message == null || message.isEmpty()) return;

            // Deserialize the message object
            Message msg = ConversionUtil.fromJson(message, Message.class);
            if (msg == null || msg.type == null) return;

            // Deserialize the payload into a map
            Map<String, String> payloadMap = msg.payload != null
                    ? ConversionUtil.jsonToMap(msg.payload)
                    : null;

            // Only handle allowed message types
            if (!allowedTypes.contains(msg.type)) return;

            switch (msg.type) {

                // --------------------------------------------------------
                case BOOTSTRAP_REQUEST -> {
                    // A node is requesting the current node list
                    ConsolePrinter.printInfo("[Bootstrap] Received bootstrap request from " + msg.senderIp);
                    resolveNodesRequest(msg); // Send back current nodes
                }

                // --------------------------------------------------------
                case BOOTSTRAP_RESPONSE -> {
                    // Response from bootstrap node containing node list
                    ConsolePrinter.printInfo("[Bootstrap] Received bootstrap response from " + msg.senderIp);
                    resolveNodesResponse(payloadMap);
                }

                // --------------------------------------------------------
                case ADD_NODE_TO_BOOTSTRAP -> {
                    // A node is requesting to register itself with the bootstrap node
                    ConsolePrinter.printInfo("[Bootstrap] Received add-node request from " + msg.senderIp);
                    boolean success = resolveAddNewNodeRequest(payloadMap);
                    if (success)
                        ConsolePrinter.printSuccess("[Bootstrap] Node " + msg.senderIp + " registered successfully.");
                    else
                        ConsolePrinter.printFail("[Bootstrap] Failed to register node " + msg.senderIp);
                }

                // --------------------------------------------------------
                default -> ConsolePrinter.printWarning("[Bootstrap] Unknown direct message type: " + msg.type);
            }

        } catch (Exception e) {
            ConsolePrinter.printFail("[Bootstrap] Error processing direct message: " + e.getMessage());
        }
    }


    @Override
    public void onMulticastMessage(String message) {

    }

    public void register(NetworkManager networkManager){
        this.networkManager = networkManager;
        networkManager.registerHandler(this);

    }

    public void stop(){
        this.networkManager.stop();
        this.networkManager.unregisterHandler(this);
    }

    public void setListener(NodeBootstrapListener listener) {
        this.listener = listener;
    }
}
