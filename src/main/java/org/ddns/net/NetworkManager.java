package org.ddns.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.ddns.chain.Role;
import org.ddns.util.ConsolePrinter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The {@code NetworkManager} class manages all peer-to-peer network communication
 * across the decentralized dDNS network.
 *
 * <p>
 * Features:
 * <ul>
 *   <li>UDP broadcast for node discovery</li>
 *   <li>UDP multicast for leader communication</li>
 *   <li>TCP direct messaging for one-to-one communication</li>
 *   <li>Extensible MessageHandler registration for modular event handling</li>
 * </ul>
 */
public class NetworkManager {

    // --- Network Configuration ---
    public static final int BROADCAST_PORT = 6969;
    public static final int DIRECT_MESSAGE_PORT = 6970;
    private static final String MULTICAST_GROUP_IP = "230.0.0.1";
    public static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Thread pool
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Handlers registered for message events
    private final List<MessageHandler> handlers = new CopyOnWriteArrayList<>();

    private volatile boolean running = true;



    // ---------------------------------------------------------------------
    //                         HANDLER MANAGEMENT
    // ---------------------------------------------------------------------

    /**
     * Registers a message handler that will receive network messages.
     */
    public void registerHandler(MessageHandler handler) {
        handlers.add(handler);
        ConsolePrinter.printInfo("[NetworkManager] Registered handler: " + handler.getClass().getSimpleName());
    }

    /**
     * Unregisters a previously registered message handler.
     */
    public void unregisterHandler(MessageHandler handler) {
        handlers.remove(handler);
        ConsolePrinter.printWarning("[NetworkManager] Unregistered handler: " + handler.getClass().getSimpleName());
    }

    // ---------------------------------------------------------------------
    //                         SENDING METHODS
    // ---------------------------------------------------------------------

    /**
     * Sends a JSON message to all devices on the local network using UDP broadcast.
     */
    public static void broadcast(String jsonMessage) {
        try (MulticastSocket socket = new MulticastSocket()) {
            byte[] buffer = jsonMessage.getBytes();
            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, broadcastAddress, BROADCAST_PORT);
            socket.send(packet);

            ConsolePrinter.printSuccess("[NetworkManager] Broadcast message sent to all nodes.");
        } catch (Exception e) {
            ConsolePrinter.printFail("[NetworkManager] Failed to send broadcast: " + e.getMessage());
        }
    }

    /**
     * Sends a JSON message directly to a specific peer via TCP.
     */
    public static boolean sendDirectMessage(String peerIp, String jsonMessage) {
        try (Socket socket = new Socket(peerIp, DIRECT_MESSAGE_PORT);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(jsonMessage);
            ConsolePrinter.printInfo("[NetworkManager] Sent direct message to " + peerIp);
            return true;
        } catch (Exception e) {
            ConsolePrinter.printFail("[NetworkManager] Failed to send direct message to " + peerIp + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Sends a JSON message to all subscribed leader nodes using UDP multicast.
     */
    public static void sendMulticast(String jsonMessage) {
        try (MulticastSocket socket = new MulticastSocket()) {
            byte[] buffer = jsonMessage.getBytes();
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP_IP);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, BROADCAST_PORT);
            socket.send(packet);

            ConsolePrinter.printSuccess("[NetworkManager] Multicast message sent to leader group.");
        } catch (Exception e) {
            ConsolePrinter.printFail("[NetworkManager] Failed to send multicast: " + e.getMessage());
        }
    }

    /**
     * Broadcasts a JSON message to specific roles among known nodes.
     */
    public static boolean broadcast(String jsonMessage, Set<NodeConfig> nodeConfigSet, Set<Role> roles) {
        if (nodeConfigSet == null || nodeConfigSet.isEmpty()) {
            ConsolePrinter.printFail("[NetworkManager] No nodes found to send broadcast message.");
            return false;
        }

        int sentCount = 0;
        for (NodeConfig nodeConfig : nodeConfigSet) {
            if (roles.contains(nodeConfig.getRole())) {
                boolean success = sendDirectMessage(nodeConfig.getIp(), jsonMessage);
                if (success) sentCount++;
            }
        }

        if (sentCount > 0) {
            ConsolePrinter.printSuccess("[NetworkManager] Broadcasted message to " + sentCount + " nodes.");
        } else {
            ConsolePrinter.printWarning("[NetworkManager] No nodes matched the specified roles for broadcast.");
        }
        return true;
    }

    // ---------------------------------------------------------------------
    //                         LISTENER MANAGEMENT
    // ---------------------------------------------------------------------

    /**
     * Starts background listener threads for UDP broadcasts, multicasts, and TCP messages.
     */
    public void startListeners() {
        ConsolePrinter.printInfo("[NetworkManager] Starting network listeners...");
        executor.submit(this::listenForUdpBroadcastsAndMulticasts);
        executor.submit(this::listenForTcpDirectMessages);
    }

    /**
     * Stops all active listeners and shuts down the thread pool.
     */
    public void stop() {
        this.running = false;
        executor.shutdownNow();
        ConsolePrinter.printWarning("[NetworkManager] Network listeners stopped.");
    }

    // ---------------------------------------------------------------------
    //                         INTERNAL HANDLERS
    // ---------------------------------------------------------------------

    private void dispatchBroadcast(String message) {
        for (MessageHandler handler : handlers) {
            handler.onBroadcastMessage(message);
        }
    }

    private void dispatchDirect(String message) {
        for (MessageHandler handler : handlers) {
            handler.onDirectMessage(message);
        }
    }

    private void dispatchMulticast(String message) {
        for (MessageHandler handler : handlers) {
            handler.onMulticastMessage(message);
        }
    }

    // ---------------------------------------------------------------------
    //                         LISTENING THREADS
    // ---------------------------------------------------------------------

    private void listenForUdpBroadcastsAndMulticasts() {
        try (MulticastSocket socket = new MulticastSocket(BROADCAST_PORT)) {
            socket.setReuseAddress(true);
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP_IP);
            socket.joinGroup(group);

            ConsolePrinter.printInfo("[NetworkManager] Listening for UDP Broadcasts and Multicasts on port " + BROADCAST_PORT);

            while (running) {
                byte[] buffer = new byte[65535];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());

                if (packet.getAddress().isMulticastAddress()) {
                    dispatchMulticast(message);
                } else {
                    dispatchBroadcast(message);
                }
            }
        } catch (Exception e) {
            if (running) {
                ConsolePrinter.printFail("[NetworkManager] UDP listener error: " + e.getMessage());
            }
        }
    }

    private void listenForTcpDirectMessages() {
        try (ServerSocket serverSocket = new ServerSocket(DIRECT_MESSAGE_PORT)) {
            ConsolePrinter.printInfo("[NetworkManager] Listening for TCP Direct Messages on port " + DIRECT_MESSAGE_PORT);
            while (running) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                        String message = in.readLine();
                        if (message != null) {
                            dispatchDirect(message);
                        }
                    } catch (IOException e) {
                        ConsolePrinter.printFail("[NetworkManager] Error reading direct message: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            if (running) {
                ConsolePrinter.printFail("[NetworkManager] TCP listener error: " + e.getMessage());
            }
        }
    }
}
