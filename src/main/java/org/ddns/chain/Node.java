package org.ddns.chain;

import com.google.gson.Gson;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.ddns.bc.*; // Your blockchain package
import org.ddns.net.Message;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;

public class Node {
    private static final Gson gson = new Gson();

    private final Wallet wallet = new Wallet();
    private final NetworkManager networkManager;
    private Blockchain blockchain; // Will be initialized after joining

    private final String ownIpAddress;
    private String role = "Idle";
    private final Map<String, String> leaderInfo = new ConcurrentHashMap<>(); // PubKey -> IP Address
    private final Map<String, List<String>> voteCollector = new ConcurrentHashMap<>(); // RequestHash -> List of Votes

    public Node() {
        this.ownIpAddress = getLocalIpAddress();

        // Define the callback functions for the NetworkManager
        Consumer<String> handleBroadcast = this::handleBroadcastMessage;
        Consumer<String> handleDirectMsg = this::handleDirectMessage;
        Consumer<String> handleMulticast = (msg) -> {}; // Placeholder

        this.networkManager = new NetworkManager(handleBroadcast, handleDirectMsg, handleMulticast);
    }

    public void start() {
        networkManager.startListeners();
        runCLI();
    }

    // --- Message Handlers ---

    private void handleBroadcastMessage(String jsonMsg) {
        Message msg = gson.fromJson(jsonMsg, Message.class);
        System.out.println("\n[Network] Received Broadcast: " + msg.type);

        switch (msg.type) {
            case DISCOVERY_REQUEST:
                // If I am a leader, I should respond.
                if ("Leader".equals(role) || "Genesis & Leader".equals(role)) {
                    System.out.println("I am a leader. Replying to discovery request...");
                    Message ack = new Message(MessageType.DISCOVERY_ACK, ownIpAddress, wallet.getKeyPair().getPublic(), gson.toJson(leaderInfo));
                    networkManager.sendDirectMessage(msg.senderIp, NetworkManager.toJson(ack));
                }
                break;
            case DNS_REGISTER_TX:
                // A leader would add this to their pending transaction pool
                // For now, we just print it
                System.out.println("DNS Registration transaction received: " + msg.payload);
                break;
            // Other cases for handling broadcasted blocks, etc.
        }
    }

    private void handleDirectMessage(String jsonMsg) {
        Message msg = gson.fromJson(jsonMsg, Message.class);
        System.out.println("\n[Network] Received Direct Message: " + msg.type + " from " + msg.senderIp);
        // This is where we would handle incoming votes, sync responses, etc.
    }

    // --- Core Functionalities ---

    private void joinNetwork() {
        System.out.println("Broadcasting discovery request to find network leaders...");
        this.role = "Discovering";
        Message discoveryMsg = new Message(MessageType.DISCOVERY_REQUEST, ownIpAddress, wallet.getKeyPair().getPublic(), "");
        networkManager.broadcast(NetworkManager.toJson(discoveryMsg));

        // Wait for 5 seconds to see if any leaders respond
        try {
            TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // In a real app, the handleDirectMessage would populate a list of replies.
        // For this simulation, we'll check a placeholder.
        boolean gotReply = false; // This would be based on actual replies.

        if (!gotReply) {
            System.out.println("No leaders responded. I am the first node. Becoming Genesis Node.");
            becomeGenesisNode();
        } else {
            System.out.println("Leader(s) responded. Starting formal join process.");
            // Here we would broadcast the JOIN_REQUEST_TX and wait for votes
        }
    }

    private void becomeGenesisNode() {
        this.role = "Genesis & Leader";
        this.blockchain = new Blockchain(List.of(wallet.getKeyPair().getPublic()));
        this.leaderInfo.put(SignatureUtil.getStringFromKey(wallet.getKeyPair().getPublic()), ownIpAddress);
        System.out.println("Genesis Node initialization complete. I am the first leader.");
    }

    private void createDnsRecord(Scanner scanner) {
        if (blockchain == null) {
            System.out.println("You must join a network before creating records.");
            return;
        }
        System.out.print("Enter domain name: ");
        String domain = scanner.nextLine();
        System.out.print("Enter IP address: ");
        String ip = scanner.nextLine();

        Transaction tx = new Transaction(wallet.getKeyPair().getPublic(), TransactionType.REGISTER, domain, Map.of("ip", ip));
        tx.sign(wallet.getKeyPair().getPrivate());

        Message msg = new Message(MessageType.DNS_REGISTER_TX, ownIpAddress, wallet.getKeyPair().getPublic(), gson.toJson(tx));

        System.out.println("Broadcasting DNS registration transaction...");
        networkManager.broadcast(NetworkManager.toJson(msg));
    }

    // --- CLI and Utilities ---

    private void runCLI() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("\n--- dDNS Node CLI ---");
            System.out.println("1. Join Network");
            System.out.println("2. View Votings (Not Implemented)");
            System.out.println("3. Register DNS Record");
            System.out.println("4. Update DNS Record (Not Implemented)");
            System.out.println("5. Delete DNS Record (Not Implemented)");
            System.out.println("6. View DNS Record (Not Implemented)");
            System.out.println("7. View My Role");
            System.out.println("0. Exit");
            System.out.print("> ");

            String choice = scanner.nextLine();
            switch (choice) {
                case "1": joinNetwork(); break;
                case "3": createDnsRecord(scanner); break;
                case "7": System.out.println("Current Role: " + this.role); break;
                case "0": networkManager.stop(); return;
                default: System.out.println("Option not yet implemented."); break;
            }
        }
    }

    private static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while(addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isSiteLocalAddress()) return addr.getHostAddress();
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
