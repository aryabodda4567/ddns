package org.ddns.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.ddns.consensus.QueueNode;
import org.ddns.constants.Role;
import org.ddns.db.BootstrapDB;
import org.ddns.db.DBUtil;
import org.ddns.net.Message;
import org.ddns.net.MessageHandler;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.node.NodeConfig;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;

import java.security.PublicKey;
import java.util.Map;
import java.util.Set;

public class BootstrapNode implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(BootstrapNode.class);

    // Bind to NetworkManager on construction
    public BootstrapNode(NetworkManager networkManager) {
        networkManager.registerHandler(this);
        log.info("[BootstrapNode] Registered with NetworkManager. Ready to serve.");
    }

    @Override
    public void onBroadcastMessage(String message) {
        log.debug("Received unhandled broadcast message.");
    }

    @Override
    public void onDirectMessage(String message) {
        Message requestMessage;
        if (message == null || message.isEmpty()) {
            log.warn("[BootstrapNode] Received null or empty direct message.");
            return;
        }
        try {
            requestMessage = ConversionUtil.fromJson(message, Message.class);
        } catch (Exception e) {
            log.error("[BootstrapNode] Failed to parse incoming message: " + e.getMessage());
            return; // Can't proceed
        }

        if (requestMessage == null || requestMessage.type == null || requestMessage.payload == null) {
            log.warn("[BootstrapNode] Received malformed message (null type or payload).");
            return;
        }

        log.info("[BootstrapNode] Received request: " + requestMessage.type +
                " from " + requestMessage.senderIp);

        try {
            switch (requestMessage.type) {
                case FETCH_NODES -> resolveGetRequest(requestMessage);
                case ADD_NODE -> resolveAddRequest(requestMessage);
                case DELETE_NODE -> resolveRemoveRequest(requestMessage);
                case PROMOTE_NODE -> resolvePromoteRequest(requestMessage);
                default -> {
                    log.warn("[BootstrapNode] Received unhandled message type: " + requestMessage.type);
                }
            }
        } catch (Exception e) {
            log.error("[BootstrapNode] Unhandled exception processing message type "
                    + requestMessage.type + ": " + e.getMessage());
            e.printStackTrace(); // Print stack trace for debugging
        }
    }

    @Override
    public void onMulticastMessage(String message) {
        log.debug("Received unhandled multicast message.");
    }

    // --- RESOLVERS (Handle incoming requests from nodes) ---

    private void resolveGetRequest(Message requestMessage) throws Exception {
        String senderIp = requestMessage.senderIp;

        if (senderIp == null || senderIp.isEmpty()) {
            log.warn("[BootstrapNode] Cannot resolve FETCH_NODES: Sender IP is missing.");
            return; // Cannot reply
        }

        Set<NodeConfig> nodeConfigSet = BootstrapDB.getInstance().getAllNodes();
        PublicKey myPublicKey = null;
        try {
            myPublicKey = DBUtil.getInstance().getPublicKey();
        } catch (Exception e) {
            log.warn("[BootstrapNode] Failed to get local public key for response: " + e.getMessage());
        }

        Message response = new Message(
                MessageType.FETCH_NODES_RESPONSE,
                NetworkUtility.getLocalIpAddress(),
                myPublicKey,
                ConversionUtil.toJson(nodeConfigSet));

        log.info("[BootstrapNode] Sending node list (" + nodeConfigSet.size() +
                " nodes) to " + senderIp);

        NetworkManager.sendDirectMessage(senderIp, ConversionUtil.toJson(response));
    }

    private void resolveAddRequest(Message requestMessage) {
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(requestMessage.payload, NodeConfig.class);

            if (nodeConfig == null || nodeConfig.getPublicKey() == null || nodeConfig.getIp() == null) {
                log.warn("[BootstrapNode] Invalid ADD_NODE request payload: " + requestMessage.payload);
                return;
            }
            // Force egalitarian role
            nodeConfig.setRole(Role.NONE);

            BootstrapDB.getInstance().saveNode(nodeConfig);
            log.info("[BootstrapNode] Added/Updated node: " + nodeConfig.getIp());

            // Update the queue
            int next = BootstrapDB.getInstance().getNextQueueSequence();
            QueueNode queueNode = new QueueNode(nodeConfig, next);
            BootstrapDB.getInstance().insertQueueNode(queueNode);

            // --- CHANGE ---
            // Pass the *current* full node list to the broadcast helper
            broadcastNodeUpdate(
                    MessageType.ADD,
                    nodeConfig,
                    BootstrapDB.getInstance().getAllNodes() // Get the list AFTER adding
            );

            // Broadcast queue update to ALL nodes
            Set<QueueNode> queueNodeSet = BootstrapDB.getInstance().getAllQueueNodes();
            Message message = new Message(
                    MessageType.QUEUE_UPDATE,
                    NetworkUtility.getLocalIpAddress(),
                    null,
                    ConversionUtil.toJson(queueNodeSet));

            NetworkManager.broadcast(ConversionUtil.toJson(message),
                    BootstrapDB.getInstance().getAllNodes(),
                    Set.of(Role.ANY));

        } catch (Exception e) {
            log.error("[BootstrapNode] Error processing ADD_NODE request: " + e.getMessage());
        }
    }

    // --- THIS IS THE CORRECTED METHOD ---
    private void resolveRemoveRequest(Message requestMessage) {
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(requestMessage.payload, NodeConfig.class);

            if (nodeConfig == null || nodeConfig.getPublicKey() == null) {
                log.warn("[BootstrapNode] Invalid DELETE_NODE request payload: " + requestMessage.payload);
                return;
            }

            // --- BUG FIX ---
            // 1. Get the list of all nodes BEFORE deleting the target node.
            Set<NodeConfig> nodesToInform = BootstrapDB.getInstance().getAllNodes();

            // 2. Now, delete the node from the database.
            BootstrapDB.getInstance().deleteNode(nodeConfig.getPublicKey());
            log.info("[BootstrapNode] Deleted node: " + nodeConfig.getIp());

            // 3. Broadcast the update using the list from step 1.
            broadcastNodeUpdate(MessageType.DELETE, nodeConfig, nodesToInform);
            // --- END FIX ---

        } catch (Exception e) {
            log.error("[BootstrapNode] Error processing DELETE_NODE request: " + e.getMessage());
        }
    }

    private void resolvePromoteRequest(Message requestMessage) {
        try {
            NodeConfig nodeConfig = ConversionUtil.fromJson(requestMessage.payload, NodeConfig.class);

            if (nodeConfig == null || nodeConfig.getPublicKey() == null) {
                log.warn(
                        "[BootstrapNode] Invalid PROMOTE_NODE request payload: " + requestMessage.payload);
                return;
            }

            // No role promotion needed - all nodes are equal
            log.info("[BootstrapNode] Node update: " + nodeConfig.getIp());

            // --- CHANGE ---
            // Pass the *current* full node list to the broadcast helper
            broadcastNodeUpdate(
                    MessageType.PROMOTE,
                    nodeConfig,
                    BootstrapDB.getInstance().getAllNodes());

        } catch (Exception e) {
            log.error("[BootstrapNode] Error processing PROMOTE_NODE request: " + e.getMessage());
        }
    }

    // --- HELPER METHOD (UPDATED) ---

    /**
     * Helper to broadcast a node list change (ADD, DELETE, PROMOTE)
     * to all relevant nodes in the network.
     *
     * @param type               The type of update (ADD_NODE, DELETE_NODE,
     *                           PROMOTE_NODE).
     * @param nodeConfig         The NodeConfig that is the subject of the update.
     * @param nodesToBroadcastTo The set of nodes to send the broadcast to.
     */
    private void broadcastNodeUpdate(MessageType type, NodeConfig nodeConfig, Set<NodeConfig> nodesToBroadcastTo) {
        log.info("[BootstrapNode] Broadcasting " + type + " for node " + nodeConfig.getIp());
        try {
            Message message = new Message(
                    type,
                    NetworkUtility.getLocalIpAddress(),
                    null, // Signed by the Bootstrap node
                    ConversionUtil.toJson(nodeConfig));

            // Update the queue
            int next = BootstrapDB.getInstance().getNextQueueSequence();
            QueueNode queueNode = new QueueNode(nodeConfig, next);
            BootstrapDB.getInstance().insertQueueNode(queueNode);

            // Use the provided list, not a fresh DB call

            NetworkManager.broadcast(
                    ConversionUtil.toJson(message),
                    nodesToBroadcastTo,
                    Set.of(Role.ANY) // All nodes
            );

            // Broadcast queue update to ALL nodes
            Set<QueueNode> queueNodeSet = BootstrapDB.getInstance().getAllQueueNodes();
            Message message1 = new Message(
                    MessageType.QUEUE_UPDATE,
                    NetworkUtility.getLocalIpAddress(),
                    null,
                    ConversionUtil.toJson(queueNodeSet));

            NetworkManager.broadcast(ConversionUtil.toJson(message1),
                    BootstrapDB.getInstance().getAllNodes(),
                    Set.of(Role.ANY));

        } catch (Exception e) {
            log.error("[BootstrapNode] Failed to broadcast node update (" + type + "): " + e.getMessage());
        }
    }
}
