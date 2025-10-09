package org.ddns.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A self-contained, low-coupled manager for all peer-to-peer network communications.
 * It handles UDP broadcasts, UDP multicast for leaders, and TCP direct messaging.
 * All listening activities are run in a background thread pool.
 */
public class NetworkManager {

    // --- Network Configuration ---
    public static final int BROADCAST_PORT = 6969; // Port for UDP broadcast and multicast
    public static final int DIRECT_MESSAGE_PORT = 6970; // Port for TCP direct messages
    private static final String MULTICAST_GROUP_IP = "230.0.0.1"; // Special IP for leader communication

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    // --- Callbacks for Decoupling ---
    // These functions will be provided by the main application to handle incoming messages.
    private final Consumer<String> onBroadcastReceived;
    private final Consumer<String> onDirectMessageReceived;
    private final Consumer<String> onMulticastReceived;

    public static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Constructs the NetworkManager.
     * @param onBroadcastReceived   A function to call when a broadcast message is received.
     * @param onDirectMessageReceived A function to call when a direct TCP message is received.
     * @param onMulticastReceived   A function to call when a leader-only multicast is received.
     */
    public NetworkManager(Consumer<String> onBroadcastReceived,
                          Consumer<String> onDirectMessageReceived,
                          Consumer<String> onMulticastReceived) {
        this.onBroadcastReceived = onBroadcastReceived;
        this.onDirectMessageReceived = onDirectMessageReceived;
        this.onMulticastReceived = onMulticastReceived;
    }


    /**
     * Starts all background listener threads.
     */
    public void startListeners() {
        System.out.println("Starting network listeners...");
        executor.submit(this::listenForUdpBroadcastsAndMulticasts);
        executor.submit(this::listenForTcpDirectMessages);
    }

    /**
     * Stops all network listeners and shuts down the thread pool.
     */
    public void stop() {
        this.running = false;
        executor.shutdownNow();
        System.out.println("Network listeners stopped.");
    }

    // --- Sending Methods ---

    /**
     * Sends a message to everyone on the local network using UDP broadcast.
     * @param jsonMessage The message to send, in JSON format.
     */
    public static void broadcast(String jsonMessage) {
        try (MulticastSocket socket = new MulticastSocket()) {
            byte[] buffer = jsonMessage.getBytes();
            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, broadcastAddress, BROADCAST_PORT);
            socket.send(packet);
        } catch (Exception e) {
            System.err.println("Failed to send broadcast: " + e.getMessage());
        }
    }

    /**
     * Sends a message directly to a specific peer using a reliable TCP connection.
     * @param peerIp The IP address of the recipient.
     * @param jsonMessage The message to send, in JSON format.
     */
    public static void sendDirectMessage(String peerIp, String jsonMessage) {
        try (Socket socket = new Socket(peerIp, DIRECT_MESSAGE_PORT);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(jsonMessage);
        } catch (Exception e) {
            System.err.println("Failed to send direct message to " + peerIp + ": " + e.getMessage());
        }
    }

    /**
     * Sends a message to only the subscribed leaders using UDP multicast.
     * @param jsonMessage The message to send, in JSON format.
     */
    public static void sendMulticast(String jsonMessage) {
        try (MulticastSocket socket = new MulticastSocket()) {
            byte[] buffer = jsonMessage.getBytes();
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP_IP);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, BROADCAST_PORT);
            socket.send(packet);
        } catch (Exception e) {
            System.err.println("Failed to send multicast: " + e.getMessage());
        }
    }

    // --- Listening Methods (Run in background threads) ---

    private void listenForUdpBroadcastsAndMulticasts() {
        try (MulticastSocket socket = new MulticastSocket(BROADCAST_PORT)) {
            socket.setReuseAddress(true); // Allow other services to use the port
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP_IP);
            socket.joinGroup(group); // Join the leader-only multicast group

            System.out.println("Listening for UDP Broadcasts and Multicasts on port " + BROADCAST_PORT);

            while (running) {
                byte[] buffer = new byte[65535];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());

                if (packet.getAddress().isMulticastAddress()) {
                    onMulticastReceived.accept(message);
                } else {
                    onBroadcastReceived.accept(message);
                }
            }
        } catch (Exception e) {
            if (running) System.err.println("UDP listener error: " + e.getMessage());
        }
    }

    private void listenForTcpDirectMessages() {
        try (ServerSocket serverSocket = new ServerSocket(DIRECT_MESSAGE_PORT)) {
            System.out.println("Listening for TCP Direct Messages on port " + DIRECT_MESSAGE_PORT);
            while (running) {
                Socket clientSocket = serverSocket.accept();
                // Handle each client in a new thread to avoid blocking the listener
                executor.submit(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                        String message = in.readLine();
                        if (message != null) {
                            onDirectMessageReceived.accept(message);
                        }
                    } catch (IOException e) {
                        System.err.println("Error reading direct message: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            if (running) System.err.println("TCP listener error: " + e.getMessage());
        }
    }

}