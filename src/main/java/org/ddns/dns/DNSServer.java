package org.ddns.dns;

import org.ddns.util.ConsolePrinter;
import org.xbill.DNS.Message;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DNSServer - UDP and TCP DNS listener that delegates query handling to {@link DNSHandler}.
 * <p>
 * Production-ready improvements included:
 * - Configurable bind address and ports via constructor / command-line args
 * - Reuse-safe socket options (SO_REUSEADDR) and adjustable buffer sizes
 * - Controlled thread pool (configurable size) instead of unbounded cached pool
 * - Proper resource cleanup and graceful shutdown
 * - Defensive handling for truncated/oversized UDP packets
 * - Clear exit messages and helpful startup logs
 */
public class DNSServer {

    // Default constants
    public static final int DEFAULT_PORT = 53;
    public static final String ORIGIN ="anits.in.";
    public static final String DEFAULT_BIND_ADDR = "0.0.0.0"; // listen on all interfaces
    public static final int DEFAULT_THREAD_POOL = 64;
    public static final int DEFAULT_MAX_UDP_PACKET = 4096; // safe common size
    public static final int TCP_READ_TIMEOUT_MS = 30_000; // 30s for TCP connections

    private final String bindAddress;
    private final int udpPort;
    private final int tcpPort;
    private final DNSHandler handler;
    // Executor used for handling individual requests and connections.
    private final ExecutorService pool;
    private volatile boolean running = false;
    // socket references so we can close them on shutdown
    private DatagramSocket udpSocket;
    private ServerSocket tcpServerSocket;

    public DNSServer(DNSHandler handler, String bindAddress, int udpPort, int tcpPort, int threadPoolSize) {
        if (handler == null) throw new IllegalArgumentException("handler required");
        this.handler = handler;
        this.bindAddress = bindAddress == null ? DEFAULT_BIND_ADDR : bindAddress;
        this.udpPort = udpPort <= 0 ? DEFAULT_PORT : udpPort;
        this.tcpPort = tcpPort <= 0 ? DEFAULT_PORT : tcpPort;
        this.pool = Executors.newFixedThreadPool(Math.max(4, threadPoolSize));
    }

    // --- Convenience main for development / CI runs ---
    public static void main(String[] args) throws Exception {
        // parse args: [bindAddress] [port]
        String bind = DEFAULT_BIND_ADDR;
        int port = DEFAULT_PORT;
        int threads = DEFAULT_THREAD_POOL;

        if (args.length >= 1) bind = args[0];
        if (args.length >= 2) port = Integer.parseInt(args[1]);
        if (args.length >= 3) threads = Integer.parseInt(args[2]);

        InMemoryDNSPersistence persistence = new InMemoryDNSPersistence();
        DNSHandler handler = new DNSHandler(persistence, ORIGIN);
        DNSServer server = new DNSServer(handler, bind, port, port, threads);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
            } catch (Exception e) {
                ConsolePrinter.printWarning("Error while shutting down: " + e);
            }
        }));

        server.start();
        ConsolePrinter.printInfo("DNSServer running. Ctrl+C to stop.");
    }

    /**
     * Start both UDP and TCP listeners. This method returns after listeners are accepted and threads spawned.
     */
    public synchronized void start() throws IOException {
        if (running) throw new IllegalStateException("Server already running");
        running = true;

        // Start UDP listener
        startUdp();

        // Start TCP listener
        startTcp();

        ConsolePrinter.printInfo(String.format("DNSServer started (bind=%s udpPort=%d tcpPort=%d)", bindAddress, udpPort, tcpPort));
    }

    private void startUdp() throws SocketException, UnknownHostException {
        InetAddress bindAddr = InetAddress.getByName(bindAddress);
        DatagramSocket socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        // bind explicitly so we can set SO_REUSEADDR before binding
        socket.bind(new InetSocketAddress(bindAddr, udpPort));
        socket.setReceiveBufferSize(DEFAULT_MAX_UDP_PACKET * 2);
        this.udpSocket = socket;

        pool.submit(() -> {
            ConsolePrinter.printInfo("UDP DNS server listening on " + bindAddress + ":" + udpPort);
            final int maxPacket = DEFAULT_MAX_UDP_PACKET;
            byte[] buffer = new byte[maxPacket];

            while (running && !socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    // copy packet data to avoid corruption by concurrent receives
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());

                    pool.submit(() -> handleUdpPacket(data, packet.getAddress(), packet.getPort()));
                } catch (SocketException se) {
                    if (running) ConsolePrinter.printFail("UDP socket error: " + se);
                    break;
                } catch (IOException e) {
                    ConsolePrinter.printWarning("UDP receive IO error: " + e);
                } catch (Throwable t) {
                    ConsolePrinter.printFail("Unexpected error in UDP listener: " + t);
                }
            }

            ConsolePrinter.printInfo("UDP listener exiting");
        });
    }

    private void handleUdpPacket(byte[] data, InetAddress addr, int port) {
        try {
            Message query = new Message(data);
            Message reply = handler.handle(query);
            byte[] out = reply.toWire();

            // Enforce MTU-like safety: if reply too large prefer to truncate or respond with FORMERR
            if (out.length > DEFAULT_MAX_UDP_PACKET) {
                ConsolePrinter.printWarning(String.format("UDP reply too large (%d bytes) for %s:%d - truncating response", out.length, addr, port));
                byte[] truncated = new byte[DEFAULT_MAX_UDP_PACKET];
                System.arraycopy(out, 0, truncated, 0, truncated.length);
                out = truncated;
            }

            DatagramPacket resp = new DatagramPacket(out, out.length, addr, port);
            synchronized (udpSocket) {
                udpSocket.send(resp);
            }
        } catch (IOException e) {
            ConsolePrinter.printWarning("Failed to handle UDP packet from " + addr + ":" + port + " -> " + e);
        } catch (Exception e) {
            ConsolePrinter.printFail("Unhandled exception while processing UDP packet: " + e);
        }
    }

    private void startTcp() throws IOException {
        InetAddress bindAddr = InetAddress.getByName(bindAddress);
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(bindAddr, tcpPort));
        this.tcpServerSocket = ss;

        pool.submit(() -> {
            ConsolePrinter.printInfo("TCP DNS server listening on " + bindAddress + ":" + tcpPort);
            while (running && !ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    s.setSoTimeout(TCP_READ_TIMEOUT_MS);
                    pool.submit(() -> handleTcpConn(s));
                } catch (SocketException se) {
                    if (running) ConsolePrinter.printFail("TCP socket error: " + se);
                    break;
                } catch (IOException e) {
                    ConsolePrinter.printWarning("TCP accept IO error: " + e);
                } catch (Throwable t) {
                    ConsolePrinter.printFail("Unexpected error in TCP listener: " + t);
                }
            }
            ConsolePrinter.printInfo("TCP listener exiting");
        });
    }

    private void handleTcpConn(Socket s) {
        InetAddress remote = s.getInetAddress();
        int remotePort = s.getPort();
        try (Socket socket = s; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            while (running && !socket.isClosed()) {
                int hi = in.read();
                if (hi < 0) break;
                int lo = in.read();
                if (lo < 0) break;
                int len = (hi << 8) | lo;
                if (len == 0) break;
                if (len > 64 * 1024) {
                    // defensive: DNS over TCP length limit
                    ConsolePrinter.printWarning(String.format("TCP frame too large (%d) from %s:%d - closing connection", len, remote, remotePort));
                    break;
                }
                byte[] data = new byte[len];
                int read = 0;
                while (read < len) {
                    int r = in.read(data, read, len - read);
                    if (r < 0) throw new EOFException("Unexpected EOF after reading " + read + " of " + len + " bytes");
                    read += r;
                }

                // process
                try {
                    Message query = new Message(data);
                    Message reply = handler.handle(query);
                    byte[] outWire = reply.toWire();
                    byte[] lenPrefix = new byte[]{(byte) ((outWire.length >> 8) & 0xFF), (byte) (outWire.length & 0xFF)};
                    out.write(lenPrefix);
                    out.write(outWire);
                    out.flush();
                } catch (IOException e) {
                    ConsolePrinter.printWarning("Failed to process TCP query from " + remote + ":" + remotePort + " -> " + e);
                    break; // close connection on processing errors
                }
            }
        } catch (EOFException eof) {
            ConsolePrinter.printInfo("TCP client connection closed by peer: " + remote + ":" + remotePort + " - " + eof.getMessage());
        } catch (SocketException se) {
            ConsolePrinter.printInfo("TCP connection socket error with " + remote + ":" + remotePort + " - " + se.getMessage());
        } catch (IOException e) {
            ConsolePrinter.printWarning("TCP connection IO error with " + remote + ":" + remotePort + " - " + e);
        } catch (Throwable t) {
            ConsolePrinter.printFail("Unhandled exception in TCP connection handler for " + remote + ":" + remotePort + " - " + t);
        } finally {
            try {
                if (s != null && !s.isClosed()) s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Stop the server and release resources. This is idempotent.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        ConsolePrinter.printInfo("DNSServer stopping...");

        try {
            if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();
        } catch (Exception ignored) {
        }
        try {
            if (tcpServerSocket != null && !tcpServerSocket.isClosed()) tcpServerSocket.close();
        } catch (Exception ignored) {
        }

        // shutdown pool gracefully
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        ConsolePrinter.printInfo("DNSServer stopped");
    }
}
