package org.ddns.chain;

import org.ddns.bc.SignatureUtil;
import org.ddns.bc.Transaction;
import org.ddns.net.*;
import org.ddns.util.ConsolePrinter; // Import the printer utility
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.PersistentStorage;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles incoming network messages and triggers appropriate actions.
 */
public class MessageHandler {

    /**
     * Responds to a DISCOVERY_REQUEST by sending back a DISCOVERY_ACK.
     */
    public static void discoveryRequest(Message message, PublicKey publicKey) {
        String senderIp = message.senderIp;
        ConsolePrinter.printInfo("Received DISCOVERY_REQUEST from " + senderIp + ". Responding with ACK...");

        Map<String, String> map = new HashMap<>();
        map.put(Names.TOTAL_NODE_COUNT, PersistentStorage.getInt(Names.TOTAL_NODE_COUNT) + "");
        map.put(Names.TOTAL_LEADER_COUNT, PersistentStorage.getInt(Names.TOTAL_LEADER_COUNT) + "");

        Message payloadMessage = new Message(
                MessageType.DISCOVERY_ACK,
                NetworkUtility.getLocalIpAddress(),
                publicKey,
                ConversionUtil.toJson(map)
        );

        NetworkManager.sendDirectMessage(senderIp, ConversionUtil.toJson(payloadMessage));
    }

    /**
     * Creates and broadcasts a JOIN_REQUEST_TX message.
     */
    public static void createJoinRequest(PublicKey senderPublicKey) {
        Message message = new Message(
                MessageType.JOIN_REQUEST_TX,
                NetworkUtility.getLocalIpAddress(),
                senderPublicKey,
                null // No payload needed
        );
        ConsolePrinter.printInfo("--> Broadcasting JOIN_REQUEST_TX to the network...");
        NetworkManager.broadcast(ConversionUtil.toJson(message));
    }

    /**
     * Initializes node configurations from a DISCOVERY_ACK payload.
     */
    public static void initializeConfigs(Map<String, String> payload) {
        try {
            int totalNodes = Integer.parseInt(payload.get(Names.TOTAL_NODE_COUNT));
            int totalLeaders = Integer.parseInt(payload.get(Names.TOTAL_LEADER_COUNT));
            PersistentStorage.put(Names.TOTAL_NODE_COUNT, totalNodes);
            PersistentStorage.put(Names.TOTAL_LEADER_COUNT, totalLeaders);
            ConsolePrinter.printSuccess("✓ Network configuration synchronized: " + totalNodes + " nodes, " + totalLeaders + " leaders.");
        } catch (NumberFormatException e) {
            ConsolePrinter.printFail("✗ Failed to parse network configuration from ACK payload.");
        }
    }

    public static void resolveBootstrapRequest(Message message) throws Exception {
        String receiverIp = message.senderIp;
        ConsolePrinter.printInfo("Received BOOTSTRAP_REQUEST from " + receiverIp + ". Responding with node list...");

        // Assumes a single, shared Bootstrap instance
        Bootstrap bootstrap = new Bootstrap();
        String resultJson = ConversionUtil.toJson(bootstrap.getNodes());

        Map<String, String> map = new HashMap<>();
        map.put("result", resultJson);
        Message resultMessage = new Message(
                MessageType.BOOTSTRAP_RESPONSE,
                NetworkUtility.getLocalIpAddress(),
                SignatureUtil.getPublicKeyFromString(message.senderPublicKey),
                ConversionUtil.toJson(map)
        );

        NetworkManager.sendDirectMessage(receiverIp, ConversionUtil.toJson(resultMessage));
    }

    public static void resolveBootstrapResponse(Map<String, String> map) {
        ConsolePrinter.printInfo("Received BOOTSTRAP_RESPONSE. Updating local node list...");
        // CRITICAL FIX: Get the shared instance instead of creating a new one.
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.addNodes(ConversionUtil.jsonToSet(map.get("result"), SystemConfig.class));
        ConsolePrinter.printSuccess("✓ Bootstrap complete. Node list is synchronized.");
    }

    public static void createBootstrapRequest(PublicKey publicKey) {
        // Assumes a single, shared Bootstrap instance
        Bootstrap bootstrap = new Bootstrap();
        String bootstrapNodeIp = bootstrap.getBootstrapNodeIp();

        if (bootstrapNodeIp == null || bootstrapNodeIp.trim().isEmpty()) {
            ConsolePrinter.printWarning("No bootstrap node IP configured. Skipping request.");
            return;
        }

        Message message = new Message(
                MessageType.BOOTSTRAP_REQUEST,
                NetworkUtility.getLocalIpAddress(),
                publicKey,
                null
        );
        ConsolePrinter.printInfo("--> Sending BOOTSTRAP_REQUEST to " + bootstrapNodeIp + "...");
        NetworkManager.sendDirectMessage(bootstrapNodeIp, ConversionUtil.toJson(message));
    }

    public static void broadcastTransaction(Transaction transaction) {
        PublicKey publicKey = null;
        try {
            publicKey = SignatureUtil.getPublicKeyFromString(PersistentStorage.getString(Names.PUBLIC_KEY));
        } catch (Exception e) {
            ConsolePrinter.printFail("✗ Could not retrieve public key to broadcast transaction: " + e.getMessage());
            return;
        }

        Map<String, String> payloadMap = new HashMap<>();
        payloadMap.put("TRANSACTION", ConversionUtil.toJson(transaction));
        Message message = new Message(
                MessageType.TRANSACTION,
                NetworkUtility.getLocalIpAddress(),
                publicKey,
                ConversionUtil.toJson(payloadMap)
        );

        ConsolePrinter.printInfo("--> Broadcasting transaction to all leaders...");
        NetworkManager.sendToNodes(ConversionUtil.toJson(message), Role.LEADER_NODE);
        NetworkManager.sendToNodes(ConversionUtil.toJson(message), Role.GENESIS);
    }

    public static void addNodeRequest(Role role) throws Exception {
        String ip = NetworkUtility.getLocalIpAddress();
        PublicKey publicKey = SignatureUtil.getPublicKeyFromString(PersistentStorage.getString(Names.PUBLIC_KEY));
        SystemConfig systemConfig = new SystemConfig(ip, role, publicKey);

        Map<String, String> map = new HashMap<>();
        map.put("NODE", ConversionUtil.toJson(systemConfig));

        Message message = new Message(
                MessageType.ADD_NODE,
                NetworkUtility.getLocalIpAddress(),
                publicKey,
                ConversionUtil.toJson(map)
        );
        ConsolePrinter.printInfo("--> Broadcasting request to add self to the network as: " + role);
        NetworkManager.broadcast(ConversionUtil.toJson(message));
    }

    public static void addNodeResolve(Map<String, String> payLoad) {
        ConsolePrinter.printInfo("Received ADD_NODE request. Updating bootstrap list...");
        SystemConfig systemConfig = ConversionUtil.fromJson(payLoad.get("NODE"), SystemConfig.class);

        if (systemConfig == null || systemConfig.getPublicKey() == null) {
            ConsolePrinter.printFail("✗ Failed to deserialize ADD_NODE payload. Ignoring.");
            return;
        }

        // CRITICAL FIX: Get the shared instance instead of creating a new one.
        Bootstrap bootstrap = new Bootstrap();

        if (systemConfig.getRole().equals(Role.LEADER_NODE)) {
            bootstrap.addLeaderNode(systemConfig);
            ConsolePrinter.printSuccess("✓ New LEADER node added to bootstrap list: " + systemConfig.getIp());
        }
        if (systemConfig.getRole().equals(Role.NORMAL_NODE)) {
            bootstrap.addNode(systemConfig);
            ConsolePrinter.printSuccess("✓ New NORMAL node added to bootstrap list: " + systemConfig.getIp());
        }

        // Optional: Log the current state for debugging
        // ConsolePrinter.printInfo("All Nodes: " + bootstrap.getNodes());
        // ConsolePrinter.printInfo("Leader Nodes: " + bootstrap.getLeaders());
    }
}