package org.ddns.node;

import org.ddns.bc.SignatureUtil;
import org.ddns.constants.ElectionType;
import org.ddns.constants.Role;
import org.ddns.db.DBUtil;
import org.ddns.governance.Election;
import org.ddns.net.Message;
import org.ddns.net.MessageHandler;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;

import java.security.PublicKey;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
            case ADD_NODE -> resolveAddNode(payload);
            case DELETE_NODE -> resolveRemoveNode(payload);
            case PROMOTE_NODE -> resolvePromoteNode(payload);
            case FETCH_NODES_RESPONSE -> resolveFetchResponse(payload);
            default -> {
                ConsolePrinter.printInfo("[NodesManager] Ignored message type: " + incoming.type);
            }
        }
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

            // *** BUG FIX ***
            // The DBUtil.updateNodeRole method expects a PublicKeyString, not an IP.
            // We must call updateNode, which finds by PublicKey.
            String pubKeyStr = SignatureUtil.getStringFromKey(nodeConfig.getPublicKey());
            boolean success = DBUtil.getInstance().updateNode(
                    pubKeyStr,
                    Role.LEADER_NODE,
                    null // We only update the role, not the IP
            );

            if (success) {
                ConsolePrinter.printSuccess("[NodesManager] Promoted node in local DB: " + nodeConfig.getIp());
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
                createAddNodeRequest();
                NodeConfig self = DBUtil.getInstance().getSelfNode();
                if (self == null) {
                    ConsolePrinter.printFail("[NodesManager] Self node not configured; cannot promote to GENESIS.");
                    return;
                }

                // Update role to GENESIS and persist to both config store and nodes table
                self.setRole(Role.GENESIS);
                DBUtil.getInstance().setSelfNode(self);
                DBUtil.getInstance().saveRole(Role.GENESIS);

                // Ensure the nodes table contains this genesis node
                DBUtil.getInstance().addNode(self);

                ConsolePrinter.printSuccess("[NodesManager] Node promoted to GENESIS and persisted locally: " + self.getIp());
                return;
            }

            // There are existing nodes in the network: sync them into local DB
            DBUtil.getInstance().addNodes(nodeConfigSet);
            ConsolePrinter.printSuccess("[NodesManager] Synced " + nodeConfigSet.size() + " nodes from Bootstrap.");

            // Create JOIN election so this node may be accepted into the chain.
            // createElection will broadcast a Nomination representing this node.
            if (this.election == null) {
                ConsolePrinter.printFail("[NodesManager] Election subsystem not initialized; cannot create JOIN election.");
                return;
            }

            // Determine a friendly node name (use self node ip if available)
            NodeConfig selfNode = DBUtil.getInstance().getSelfNode();
            String friendlyName = (selfNode != null && selfNode.getIp() != null) ? selfNode.getIp() : NetworkUtility.getLocalIpAddress();

            // Voting window: 5 minutes by default (adjust as needed)
            final int votingMinutes = 5;
            String description = "Request to join network (JOIN election)";

            ConsolePrinter.printInfo("[NodesManager] Creating JOIN election to join the existing network.");
            election.createElection(ElectionType.JOIN, votingMinutes, friendlyName, description);

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
                    null
            );
            if (genesisNode != null)
                NetworkManager.sendDirectMessage(genesisNode.getIp(), ConversionUtil.toJson(message));
            else {
                ConsolePrinter.printFail("No genesis node found");
                return;
            }
            ConsolePrinter.printInfo("Sync request is sent to Genesis node");

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void resolveSyncRequest(Message message) {
        String requestNodeIp = message.senderIp;

        ///TODO  We need to create database snapshot of transactions and DNS records DB
    }

    public void resolveSyncResponse(String payload) {
//        TODO Network manager has a method to receive the files.
//        TODO access that file and create sql insert statements and insert them into respective dbs with correct time stamp
//
    }


}