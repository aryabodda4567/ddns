package org.ddns.bootstrap;

import org.ddns.constants.Role;
import org.ddns.db.BootstrapDB;
import org.ddns.db.DBUtil;
import org.ddns.net.Message;
import org.ddns.net.MessageHandler;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.node.NodeConfig;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;

import java.security.PublicKey;
import java.util.Set;
import java.util.logging.Logger;

public class BootstrapNode implements MessageHandler {

    // Use a proper logger or ConsolePrinter
    private static final Logger LOGGER = Logger.getLogger(BootstrapNode.class.getName());

    // Bind to NetworkManager on construction
    public BootstrapNode(NetworkManager networkManager) {
        networkManager.registerHandler(this);
        ConsolePrinter.printSuccess("[BootstrapNode] Registered with NetworkManager. Ready to serve.");
    }

    @Override
    public void onBroadcastMessage(String message) {
        LOGGER.finer("Received unhandled broadcast message.");
    }

    @Override
    public void onDirectMessage(String message) {
        Message requestMessage;
        if (message == null || message.isEmpty()) {
            ConsolePrinter.printWarning("[BootstrapNode] Received null or empty direct message.");
            return;
        }
        try {
            requestMessage = ConversionUtil.fromJson(message, Message.class);
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapNode] Failed to parse incoming message: " + e.getMessage());
            return; // Can't proceed
        }

        if (requestMessage == null || requestMessage.type == null || requestMessage.payload == null) {
            ConsolePrinter.printWarning("[BootstrapNode] Received malformed message (null type or payload).");
            return;
        }

        ConsolePrinter.printInfo("[BootstrapNode] Received request: " + requestMessage.type +
                " from " + requestMessage.senderIp);

        try {
            switch (requestMessage.type) {
                case FETCH_NODES -> resolveGetRequest(requestMessage);
                case ADD_NODE -> resolveAddRequest(requestMessage);
                case DELETE_NODE -> resolveRemoveRequest(requestMessage);
                case PROMOTE_NODE -> resolvePromoteRequest(requestMessage);
                default -> {
                    ConsolePrinter.printWarning("[BootstrapNode] Received unhandled message type: " + requestMessage.type);
                }
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapNode] Unhandled exception processing message type "
                    + requestMessage.type + ": " + e.getMessage());
            e.printStackTrace(); // Print stack trace for debugging
        }
    }

    @Override
    public void onMulticastMessage(String message) {
        LOGGER.finer("Received unhandled multicast message.");
    }

    // --- RESOLVERS (Handle incoming requests from nodes) ---

    private void resolveGetRequest(Message requestMessage) throws Exception {
        String senderIp = requestMessage.senderIp;

        if (senderIp == null || senderIp.isEmpty()) {
            ConsolePrinter.printWarning("[BootstrapNode] Cannot resolve FETCH_NODES: Sender IP is missing.");
            return; // Cannot reply
        }

        Set<NodeConfig> nodeConfigSet = BootstrapDB.getInstance().getAllNodes();
        PublicKey myPublicKey = null;
        try {
            myPublicKey = DBUtil.getInstance().getPublicKey();
        } catch (Exception e) {
            ConsolePrinter.printWarning("[BootstrapNode] Failed to get local public key for response: " + e.getMessage());
        }

        Message response = new Message(
                MessageType.FETCH_NODES_RESPONSE,
                NetworkUtility.getLocalIpAddress(),
                myPublicKey,
                ConversionUtil.toJson(nodeConfigSet)
        );

        ConsolePrinter.printInfo("[BootstrapNode] Sending node list (" + nodeConfigSet.size() +
                " nodes) to " + senderIp);

        NetworkManager.sendDirectMessage(senderIp, ConversionUtil.toJson(response));
    }

    private void resolveAddRequest(Message requestMessage) {
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(requestMessage.payload, NodeConfig.class);

            if (nodeConfig == null || nodeConfig.getPublicKey() == null || nodeConfig.getIp() == null) {
                ConsolePrinter.printWarning("[BootstrapNode] Invalid ADD_NODE request payload: " + requestMessage.payload);
                return;
            }

            BootstrapDB.getInstance().saveNode(nodeConfig);
            ConsolePrinter.printSuccess("[BootstrapNode] Added/Updated node: " + nodeConfig.getIp());

            // --- CHANGE ---
            // Pass the *current* full node list to the broadcast helper
            broadcastNodeUpdate(
                    MessageType.ADD,
                    nodeConfig,
                    BootstrapDB.getInstance().getAllNodes() // Get the list AFTER adding
            );

        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapNode] Error processing ADD_NODE request: " + e.getMessage());
        }
    }

    // --- THIS IS THE CORRECTED METHOD ---
    private void resolveRemoveRequest(Message requestMessage) {
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(requestMessage.payload, NodeConfig.class);

            if (nodeConfig == null || nodeConfig.getPublicKey() == null) {
                ConsolePrinter.printWarning("[BootstrapNode] Invalid DELETE_NODE request payload: " + requestMessage.payload);
                return;
            }

            // --- BUG FIX ---
            // 1. Get the list of all nodes BEFORE deleting the target node.
            Set<NodeConfig> nodesToInform = BootstrapDB.getInstance().getAllNodes();

            // 2. Now, delete the node from the database.
            BootstrapDB.getInstance().deleteNode(nodeConfig.getPublicKey());
            ConsolePrinter.printSuccess("[BootstrapNode] Deleted node: " + nodeConfig.getIp());

            // 3. Broadcast the update using the list from step 1.
            broadcastNodeUpdate(MessageType.DELETE, nodeConfig, nodesToInform);
            // --- END FIX ---

        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapNode] Error processing DELETE_NODE request: " + e.getMessage());
        }
    }

    private void resolvePromoteRequest(Message requestMessage) {
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(requestMessage.payload, NodeConfig.class);

            if (nodeConfig == null || nodeConfig.getPublicKey() == null) {
                ConsolePrinter.printWarning("[BootstrapNode] Invalid PROMOTE_NODE request payload: " + requestMessage.payload);
                return;
            }

            BootstrapDB.getInstance().updateNode(nodeConfig.getPublicKey(), Role.LEADER_NODE, nodeConfig.getIp());
            nodeConfig.setRole(Role.LEADER_NODE);
            ConsolePrinter.printSuccess("[BootstrapNode] Promoted node: " + nodeConfig.getIp());

            // --- CHANGE ---
            // Pass the *current* full node list to the broadcast helper
            broadcastNodeUpdate(
                    MessageType.PROMOTE,
                    nodeConfig,
                    BootstrapDB.getInstance().getAllNodes()
            );

        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapNode] Error processing PROMOTE_NODE request: " + e.getMessage());
        }
    }

    // --- HELPER METHOD (UPDATED) ---

    /**
     * Helper to broadcast a node list change (ADD, DELETE, PROMOTE)
     * to all relevant nodes in the network.
     *
     * @param type               The type of update (ADD_NODE, DELETE_NODE, PROMOTE_NODE).
     * @param nodeConfig         The NodeConfig that is the subject of the update.
     * @param nodesToBroadcastTo The set of nodes to send the broadcast to.
     */
    private void broadcastNodeUpdate(MessageType type, NodeConfig nodeConfig, Set<NodeConfig> nodesToBroadcastTo) {
        ConsolePrinter.printInfo("[BootstrapNode] Broadcasting " + type + " for node " + nodeConfig.getIp());
        try {
            Message message = new Message(
                    type,
                    NetworkUtility.getLocalIpAddress(),
                    null, // Signed by the Bootstrap node
                    ConversionUtil.toJson(nodeConfig)
            );

            // Use the provided list, not a fresh DB call

            NetworkManager.broadcast(
                    ConversionUtil.toJson(message),
                    nodesToBroadcastTo, // Use the list passed in
                    Set.of(Role.GENESIS, Role.LEADER_NODE, Role.NORMAL_NODE) // Target roles
            );
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapNode] Failed to broadcast node update (" + type + "): " + e.getMessage());
        }
    }
}