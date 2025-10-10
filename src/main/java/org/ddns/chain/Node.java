package org.ddns.chain;

import org.ddns.bc.SignatureUtil;
import org.ddns.net.Message;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Represents a network node in the decentralized DNS blockchain.
 * <p>
 * Responsibilities:
 * - Maintain node's role (NORMAL, LEADER, GENESIS)
 * - Handle network communications via NetworkManager
 * - Join the network and perform discovery with leaders
 * - Participate in voting/nomination
 */
public class Node {

    private final CompletableFuture<Boolean> discoveryResponseFuture = new CompletableFuture<>();
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
        NetworkManager networkManager = new NetworkManager(
                this::handleBroadcastMessage,
                this::handleDirectMessage,
                this::handleMulticastMessage
        );

        // Start listeners in background
        networkManager.startListeners();

        //Request bootstrap node
        MessageHandler.createBootstrapRequest(this.publicKey);

        // Attempt to join the network
        joinNetwork();
        cli();
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

    }

    /**
     * Handles multicast messages (UDP multicast) for leader nodes.
     */
    private void handleMulticastMessage(String message) {

    }

    public void cli() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("1).View Votes\n2).Show nominations");
        int option = scanner.nextInt();
        if (option == 1) Governance.votingResults();
        if (option == 2) {
            List<String> nominations = Governance.getNominations();
            if (nominations.isEmpty()) {
                System.out.println("No nominations found");
            } else {
                System.out.println("Select the ip of the node to cast vote");
                for (int i = 0; i < nominations.size(); i++) {
                    System.out.println(i + "). " + nominations.get(i));
                }
                int selection = scanner.nextInt();

                Governance.castVote(nominations.get(selection), true, this.publicKey);
                Governance.updateNominations(nominations, nominations.get(selection));

            }

        }
        cli();
    }


    /**
     * Handles direct messages (TCP) from other nodes.
     */
    private void handleDirectMessage(String message) {
        Message messageObject = ConversionUtil.fromJson(message, Message.class);
        HashMap<String, String> payload = (HashMap<String, String>) ConversionUtil.jsonToMap(messageObject.payload);

        if (messageObject.type.equals(Role.LEADER_NODE) && !role.equals(Role.LEADER_NODE) && !role.equals(Role.GENESIS))
            return;

        switch (messageObject.type) {
            case DISCOVERY_ACK -> {
                discoveryResponseFuture.complete(true); // signal ACK received
                MessageHandler.initializeConfigs(payload);
            }
            case JOIN_VOTE -> {
                System.out.println("Vote received from " + messageObject.senderPublicKey);
                Boolean vote = Boolean.parseBoolean(payload.get("VOTE"));
                if (vote.equals(true)) {
                    Governance.addVote();
                }
            }
            case DISCOVERY_REQUEST -> MessageHandler.discoveryRequest(messageObject, publicKey);
            case JOIN_REQUEST_TX -> {
                System.out.println("Voting request received via broadcast");

                Governance.updateNominations(messageObject);
            }
            case BOOTSTRAP_REQUEST -> {
                try {
                    MessageHandler.resolveBootstrapRequest(messageObject);
                } catch (Exception e) {
                    System.out.println("Error in resolving in BOOTSTRAP Request " + e.getMessage());
                }
            }
            case BOOTSTRAP_RESPONSE -> MessageHandler.resolveBootstrapResponse(payload);

        }
    }


}
