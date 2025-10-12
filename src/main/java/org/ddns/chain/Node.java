package org.ddns.chain;

import org.ddns.bc.SignatureUtil;
import org.ddns.bc.Transaction;
import org.ddns.net.Message;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.PersistentStorage;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Represents a network node in the decentralized DNS blockchain.
 * Handles the node's lifecycle, state, and user interactions.
 */
public class Node {

    private final CompletableFuture<Boolean> discoveryResponseFuture = new CompletableFuture<>();
    private final NetworkManager networkManager;
    private final PublicKey publicKey;
    private Role role;

    /**
     * Constructs a new Node.
     *
     * @param privateKey The private key string (Base64), or null to generate a new keypair.
     * @throws Exception If key processing fails.
     */
    public Node(String privateKey) throws Exception {
        this.role = Role.NONE;
        ConsolePrinter.printInfo("Initializing node wallet and identity...");

        if (privateKey == null) {
            KeyPair keyPair = Wallet.getKeyPair();
            this.publicKey = keyPair.getPublic();
            PersistentStorage.put(Names.PRIVATE_KEY, SignatureUtil.getStringFromKey(keyPair.getPrivate()));
        } else {
            this.publicKey = Wallet.getPublicKeyFromPrivateKey(SignatureUtil.getPrivateKeyFromString(privateKey));
            PersistentStorage.put(Names.PRIVATE_KEY, privateKey);
        }
        PersistentStorage.put(Names.PUBLIC_KEY, SignatureUtil.getStringFromKey(publicKey));
        ConsolePrinter.printSuccess("✓ Wallet initialized successfully.");
        ConsolePrinter.printInfo("  Public Key: " + SignatureUtil.getStringFromKey(this.publicKey).substring(0, 30) + "...");

        // Initialize network manager with custom message handlers
        this.networkManager = new NetworkManager(
                this::handleBroadcastMessage,
                this::handleDirectMessage,
                this::handleMulticastMessage
        );
    }

    /**
     * Starts the node's core services: network listeners, bootstrapping, and the CLI.
     */
    public void start() throws Exception {
        networkManager.startListeners();
        MessageHandler.createBootstrapRequest(this.publicKey);
        joinNetwork();
        cli();
    }

    /**
     * Broadcasts a discovery request and transitions to Genesis role if no leaders respond.
     */
    public void joinNetwork() {
        ConsolePrinter.printInfo("Attempting to join the network...");
        ConsolePrinter.printInfo("Broadcasting DISCOVERY_REQUEST to find leaders.");

        Message message = new Message(
                MessageType.DISCOVERY_REQUEST,
                NetworkUtility.getLocalIpAddress(),
                this.publicKey,
                "" // No payload needed for discovery
        );
        NetworkManager.broadcast(ConversionUtil.toJson(message));

        try {
            boolean ackReceived = discoveryResponseFuture
                    .orTimeout(3, TimeUnit.SECONDS)
                    .exceptionally(ex -> false)
                    .get();

            if (ackReceived) {
                ConsolePrinter.printSuccess("✓ ACK received from a leader. Proceeding with network join request...");
                ConsolePrinter.printInfo("Enter voting time limit in minutes:");
                int timeLimit = new Scanner(System.in).nextInt();
                Governance.createNomination(publicKey, timeLimit);
                ConsolePrinter.printInfo("Nomination broadcasted. Wait for " + timeLimit + " minutes to view voting results.");
            } else {
                ConsolePrinter.printWarning("⚠️ No ACK received within the timeout period.");
                ConsolePrinter.printSuccess("✓ This node is now the Genesis Node.");
                this.role = Role.GENESIS;
                PersistentStorage.put(Names.ROLE, Role.GENESIS);
                // The new Genesis node adds itself as the first leader
                MessageHandler.addNodeRequest(Role.LEADER_NODE);
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("✗ An error occurred during the join process: " + e.getMessage());
        }
    }

    /**
     * Handles broadcast messages received from the network.
     */
    private void handleBroadcastMessage(String message) {
        Message msg = ConversionUtil.fromJson(message, Message.class);
        ConsolePrinter.printInfo("[BROADCAST] Received " + msg.type + " from " + msg.senderIp);
        // Here, you would add logic to handle incoming blocks, transactions, etc.
    }

    /**
     * Handles multicast messages (UDP multicast) for leader nodes.
     */
    private void handleMulticastMessage(String message) {
        Message msg = ConversionUtil.fromJson(message, Message.class);
        ConsolePrinter.printInfo("[MULTICAST] Received " + msg.type + " from " + msg.senderIp);
        // Logic for leader-specific communication would go here.
    }

    /**
     * Main Command-Line Interface loop for user interaction.
     */
    public void cli() throws Exception {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            ConsolePrinter.printInfo("\n--- Node Menu ---");
            System.out.println("1. View Votes");
            System.out.println("2. Show & Vote on Nominations");
            System.out.println("0. Exit");
            System.out.print("Choose an option: ");

            String option = scanner.nextLine();
            switch (option) {
                case "1":
                    Governance.votingResults();
                    break;
                case "2":
                    handleNominationVoting(scanner);
                    break;
                case "0":
                    ConsolePrinter.printInfo("Shutting down node...");
                    networkManager.stop();
                    return;
                default:
                    ConsolePrinter.printFail("Invalid option. Please try again.");
            }
        }
    }

    private void handleNominationVoting(Scanner scanner) throws Exception {
        List<String> nominations = Governance.getNominations();
        if (nominations.isEmpty()) {
            ConsolePrinter.printWarning("No active nominations found.");
        } else {
            ConsolePrinter.printInfo("Select the IP of the node to cast a vote for:");
            for (int i = 0; i < nominations.size(); i++) {
                System.out.println(i + "). " + nominations.get(i));
            }
            int selection = Integer.parseInt(scanner.nextLine());
            if (selection >= 0 && selection < nominations.size()) {
                Governance.castVote(nominations.get(selection), true, this.publicKey);
                Governance.updateNominations(nominations, nominations.get(selection));
            } else {
                ConsolePrinter.printFail("Invalid selection.");
            }
        }
    }

    /**
     * Handles direct messages (TCP) from other nodes.
     */
    private void handleDirectMessage(String message) {
        Message msg = ConversionUtil.fromJson(message, Message.class);
        HashMap<String, String> payload = (HashMap<String, String>) ConversionUtil.jsonToMap(msg.payload);

        // This logic prevents a normal node from acting on leader-only messages
        if (msg.type.equals(Role.LEADER_NODE) && !role.equals(Role.LEADER_NODE) && !role.equals(Role.GENESIS)) {
            return;
        }

        switch (msg.type) {
            case DISCOVERY_ACK -> {
                discoveryResponseFuture.complete(true); // Signal ACK received
                MessageHandler.initializeConfigs(payload);
            }
            case JOIN_VOTE -> {
                ConsolePrinter.printSuccess("✓ Vote received from " + msg.senderPublicKey.substring(0, 20) + "...");
                Boolean vote = Boolean.parseBoolean(payload.get("VOTE"));
                if (Boolean.TRUE.equals(vote)) {
                    Governance.addVote();
                }
            }
            case DISCOVERY_REQUEST -> MessageHandler.discoveryRequest(msg, publicKey);
            case JOIN_REQUEST_TX -> {
                ConsolePrinter.printInfo("Received a join request. Forwarding to governance module.");
                Governance.updateNominations(msg);
            }
            case BOOTSTRAP_REQUEST -> {
                try {
                    MessageHandler.resolveBootstrapRequest(msg);
                } catch (Exception e) {
                    ConsolePrinter.printFail("Error resolving BOOTSTRAP Request: " + e.getMessage());
                }
            }
            case BOOTSTRAP_RESPONSE -> MessageHandler.resolveBootstrapResponse(payload);
            case ADD_NODE -> MessageHandler.addNodeResolve(payload);
            case TRANSACTION -> {
                Transaction transaction = ConversionUtil.fromJson(payload.get("TRANSACTION"), Transaction.class);
                new ChainManager().handleRegisterTransaction(transaction); // Note: Creating a new ChainManager might be inefficient
            }
            default -> ConsolePrinter.printWarning("Received unhandled direct message type: " + msg.type);
        }
    }
}