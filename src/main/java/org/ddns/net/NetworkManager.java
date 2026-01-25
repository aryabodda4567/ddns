package org.ddns.net;


import org.ddns.constants.FileNames;
import org.ddns.constants.Role;
import org.ddns.node.NodeConfig;
import org.ddns.node.NodesManager;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.NetworkUtility;

import java.io.*;
import java.net.*;
import java.util.HashSet;
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
    public static final int FILE_TRANSFER_PORT = 6971;
    private static final String MULTICAST_GROUP_IP = "230.0.0.1";

    // Thread pool
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Handlers registered for message events
    private final List<MessageHandler> handlers = new CopyOnWriteArrayList<>();

    private volatile boolean running = true;


    // ---------------------------------------------------------------------
    //                         HANDLER MANAGEMENT
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

    // ---------------------------------------------------------------------
    //                         SENDING METHODS
    // ---------------------------------------------------------------------

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
     * Now robust against nulls, whitespace issues, and treats Role.ANY as a wildcard.
     */
    public static void broadcast(String jsonMessage, Set<NodeConfig> nodeConfigSet, Set<Role> roles) {
        if (nodeConfigSet == null || nodeConfigSet.isEmpty()) {
            ConsolePrinter.printWarning("[NetworkManager] No nodes found to send broadcast message.");
            return;
        }
        if (roles == null || roles.isEmpty()) {
            ConsolePrinter.printWarning("[NetworkManager] No roles specified for broadcast.");
            return;
        }

        String localIp = NetworkUtility.getLocalIpAddress();
        boolean broadcastAll = roles.contains(Role.ANY);
        int sentCount = 0;

        // Normalize role names for safe comparison
        Set<String> normalizedRoleNames = new HashSet<>();
        for (Role role : roles) {
            if (role != null) normalizedRoleNames.add(role.name().trim().toUpperCase());
        }

        for (NodeConfig nodeConfig : nodeConfigSet) {
            if (nodeConfig == null || nodeConfig.getIp() == null) continue;
//            if (localIp != null && localIp.equals(nodeConfig.getIp())) continue; // Skip self

            Role nodeRole = nodeConfig.getRole();
            boolean match = false;

            if (broadcastAll) {
                match = true;
            } else if (nodeRole != null && normalizedRoleNames.contains(nodeRole.name().trim().toUpperCase())) {
                match = true;
            }

            if (match) {
                boolean success = sendDirectMessage(nodeConfig.getIp(), jsonMessage);
                if (success) sentCount++;
            } else {
                ConsolePrinter.printInfo("[NetworkManager] Skipping node " + nodeConfig.getIp()
                        + " (role=" + nodeRole + "), not in target roles " + normalizedRoleNames);
            }
        }

        if (sentCount > 0) {
            ConsolePrinter.printSuccess("[NetworkManager] Broadcasted message to " + sentCount + " nodes.");
        } else {
            ConsolePrinter.printWarning("[NetworkManager] No nodes matched roles " + normalizedRoleNames);
        }
    }

    /**
     * Sends a file to the specified peer over TCP.
     *
     * @param peerIp   IP address of the receiver.
     * @param filePath Absolute path of the file to send.
     */
    public static void sendFile(String peerIp, String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            ConsolePrinter.printFail("[NetworkManager] File not found: " + filePath);
            return;
        }

        try (Socket socket = new Socket(peerIp, FILE_TRANSFER_PORT);
             FileInputStream fis = new FileInputStream(file);
             BufferedOutputStream bos = new BufferedOutputStream(socket.getOutputStream());
             DataOutputStream dos = new DataOutputStream(bos)) {

            // Send metadata
            dos.writeUTF(file.getName());
            dos.writeLong(file.length());

            // Send file data
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalSent = 0;
            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
                totalSent += bytesRead;
            }
            dos.flush();

            ConsolePrinter.printSuccess("[NetworkManager] File sent successfully (" + totalSent + " bytes) to " + peerIp);
        } catch (Exception e) {
            ConsolePrinter.printFail("[NetworkManager] Failed to send file to " + peerIp + ": " + e.getMessage());
        }
    }

    /**
     * Registers a message handler that will receive network messages.
     */
    public void registerHandler(MessageHandler handler) {
        handlers.add(handler);
        ConsolePrinter.printInfo("[NetworkManager] Registered handler: " + handler.getClass().getSimpleName());
    }


    // ---------------------------------------------------------------------
    //                         LISTENER MANAGEMENT
    // ---------------------------------------------------------------------

    /**
     * Unregisters a previously registered message handler.
     */
    public void unregisterHandler(MessageHandler handler) {
        handlers.remove(handler);
        ConsolePrinter.printWarning("[NetworkManager] Unregistered handler: " + handler.getClass().getSimpleName());
    }

    /**
     * Starts background listener threads for UDP broadcasts, multicasts, and TCP messages.
     */
    public void startListeners() {
        ConsolePrinter.printInfo("[NetworkManager] Starting network listeners...");
        executor.submit(this::listenForUdpBroadcastsAndMulticasts);
        executor.submit(this::listenForTcpDirectMessages);
        executor.submit(this::listenForFileTransfers);
    }

    // ---------------------------------------------------------------------
    //                         INTERNAL HANDLERS
    // ---------------------------------------------------------------------

    /**
     * Stops all active listeners and shuts down the thread pool.
     */
    public void stop() {
        this.running = false;
        executor.shutdownNow();
        ConsolePrinter.printWarning("[NetworkManager] Network listeners stopped.");
    }

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

    // ---------------------------------------------------------------------
    //                         LISTENING THREADS
    // ---------------------------------------------------------------------

    private void dispatchMulticast(String message) {
        for (MessageHandler handler : handlers) {
            handler.onMulticastMessage(message);
        }
    }

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


    // ---------------------------------------------------------------------
    //                         FILE TRANSFER METHODS
    // ---------------------------------------------------------------------

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

    /**
     * Listens for incoming file transfers and saves them to DNS_SNAPSHOT.
     */
    private void listenForFileTransfers() {
        try (ServerSocket serverSocket = new ServerSocket(FILE_TRANSFER_PORT)) {
            ConsolePrinter.printInfo("[NetworkManager] Listening for file transfers on port " + FILE_TRANSFER_PORT);

            while (running) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleIncomingFile(clientSocket));
            }
        } catch (Exception e) {
            if (running) {
                ConsolePrinter.printFail("[NetworkManager] File transfer listener error: " + e.getMessage());
            }
        }
    }

    /**
     * Handles a single incoming file transfer.
     */
    private void handleIncomingFile(Socket clientSocket) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()))) {
            ConsolePrinter.printInfo("[NetworkManager] Receiving file from "+ clientSocket.getInetAddress().toString());
            String originalFileName = dis.readUTF();
            long fileSize = dis.readLong();

            // Save destination: jdbc:sqlite:" + dns_temp.db;
            // => We extract just the file path from that logical connection string.
            String outputPath = FileNames.BLOCK_DB_TEMP;
            File outputFile = new File(outputPath);

            try (FileOutputStream fos = new FileOutputStream(outputFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                byte[] buffer = new byte[8192];
                long received = 0;
                int bytesRead;
                while (received < fileSize && (bytesRead = dis.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                    received += bytesRead;
                }
                bos.flush();

                ConsolePrinter.printSuccess("[NetworkManager] Received file '" + originalFileName + "' (" + received + " bytes) and saved to: " + outputPath);
                NodesManager.sync();
            }

        } catch (IOException e) {
            ConsolePrinter.printFail("[NetworkManager] Error receiving file: " + e.getMessage());
        }
    }
}
