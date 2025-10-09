package org.ddns.chain;

import org.ddns.bc.SignatureUtil;
import org.ddns.net.Message;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Represents a network node in the decentralized DNS blockchain.
 *
 * Responsibilities:
 * - Maintain node's role (NORMAL, LEADER, GENESIS)
 * - Handle network communications via NetworkManager
 * - Join the network and perform discovery with leaders
 * - Participate in voting/nomination
 */
public class Node {

    private final CompletableFuture<Boolean> discoveryResponseFuture = new CompletableFuture<>();
    private NetworkManager networkManager;
    private final PublicKey publicKey;
    private Role role;

    /**
     * Constructs a new Node.
     * Initializes public key from provided private key (or generates a new keypair if null)
     * and starts network listeners immediately.
     *
     * @param privateKey The private key string (Base64), or null to generate a new keypair.
     * @throws Exception If key processing fails.
     */
    public Node(String privateKey) throws Exception {
        this.role = Role.NONE;

        if (privateKey == null) {
            this.publicKey = Wallet.getKeyPair().getPublic();
        } else {
            this.publicKey = Wallet.getPublicKeyFromPrivateKey(
                    SignatureUtil.getPrivateKeyFromString(privateKey)
            );
        }

        // Initialize network manager with custom message handlers
        this.networkManager = new NetworkManager(
                this::handleBroadcastMessage,
                this::handleDirectMessage,
                this::handleMulticastMessage
        );

        // Start listeners in background
        networkManager.startListeners();

        // Attempt to join the network
        joinNetwork();
    }

    /**
     * Sends a DISCOVERY_REQUEST to the network and waits for ACK from leader nodes.
     * If no ACK is received within 3 seconds, the node becomes a Genesis node.
     */
    public void joinNetwork() {
        Map<String, String> payload = new HashMap<>();
        Message message = new Message(
                MessageType.DISCOVERY_REQUEST,
                NetworkUtility.getLocalIpAddress(),
                this.publicKey,
                ConversionUtil.toJson(payload)
        );

        NetworkManager.broadcast(ConversionUtil.toJson(message));

        try {
            // Wait up to 3 seconds for ACK
            boolean ackReceived = discoveryResponseFuture
                    .orTimeout(3, TimeUnit.SECONDS)
                    .exceptionally(ex -> false)
                    .get();

            if (ackReceived) {
                System.out.println("✅ ACK received from leader. Proceeding with network join request...");
                System.out.println("Enter voting time limit in minutes:");
                int timeLimit = new Scanner(System.in).nextInt();
                Governance.createNomination(publicKey, timeLimit);
                System.out.println("Wait for " + timeLimit + " minutes to view voting results.");

            } else {
                System.out.println("⚠️ No ACK received within 3 seconds. This node is now Genesis Node.");
                this.role = Role.GENESIS;
                // Genesis node initialization logic can go here
            }

        } catch (Exception e) {
            System.out.println("No ACK received (timeout or error): " + e.getMessage());
            System.out.println("Retrying network join...");
            joinNetwork();
        }
    }

    /**
     * Handles broadcast messages received from the network.
     */
    private void handleBroadcastMessage(String message) {
        Message messageObject = ConversionUtil.fromJson(message, Message.class);
        HashMap<String, String> payload = (HashMap<String, String>) ConversionUtil.jsonToMap(messageObject.payload);

//        if (MessageHandler.requiredRole(messageObject).equals(Role.LEADER_NODE)) {
//            if (!role.equals(Role.LEADER_NODE) && !role.equals(Role.GENESIS)) return;
//        }

        switch (messageObject.type) {
            case DISCOVERY_REQUEST -> MessageHandler.discoveryRequest(messageObject,publicKey);
            case JOIN_REQUEST_TX -> {
                System.out.println("Voting request received via broadcast");
                Governance.updateNominations(messageObject);
            }
        }

    }

    /**
     * Handles direct messages (TCP) from other nodes.
     */
    private void handleDirectMessage(String message) {
        Message messageObject = ConversionUtil.fromJson(message, Message.class);
        HashMap<String, String> payload = (HashMap<String, String>) ConversionUtil.jsonToMap(messageObject.payload);
        switch (messageObject.type) {
            case DISCOVERY_ACK -> {
                discoveryResponseFuture.complete(true); // signal ACK received
                MessageHandler.initializeConfigs(payload);
            }
            case JOIN_VOTE -> {
                System.out.println(messageObject.payload);
                if (Boolean.parseBoolean(messageObject.payload)) {

                    Governance.addVote();
                }
            }

        }
    }

    /**
     * Handles multicast messages (UDP multicast) for leader nodes.
     */
    private void handleMulticastMessage(String message) {
        Message messageObject = ConversionUtil.fromJson(message, Message.class);
        HashMap<String, String> payload = (HashMap<String, String>) ConversionUtil.jsonToMap(messageObject.payload);

        if (messageObject.type.equals(MessageType.JOIN_REQUEST_TX)) {
            System.out.println("Voting request received via multicast");
            Governance.updateNominations(messageObject);
        }
    }
}
