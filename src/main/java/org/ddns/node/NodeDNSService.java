package org.ddns.node;

import org.ddns.dns.DNSClient;
import org.ddns.dns.DNSHandler;
import org.ddns.dns.DNSServer;
import org.ddns.dns.InMemoryDNSPersistence;
import org.ddns.util.ConsolePrinter;

import java.net.ServerSocket;

public final class NodeDNSService {

    private static DNSServer server;
    private static DNSClient client;

    private static String origin;
    private static String bindIp;
    private static int threads;

    private static int port;
    private static volatile boolean started = false;

    private NodeDNSService() {
        // no instances
    }

    /**
     * Configure DNS service before starting.
     */
    public static synchronized void configure(String bindIp, String origin, int threads) {
        if (started) {
            throw new IllegalStateException("NodeDNSService already started");
        }
        NodeDNSService.bindIp = bindIp;
        NodeDNSService.origin = origin;
        NodeDNSService.threads = threads;
    }

    /**
     * Starts DNS server and client (only once per node)
     */
    public static synchronized void start() {
        if (started) {
            ConsolePrinter.printWarning("[NodeDNS] Already started");
            return;
        }

        try {
            if (bindIp == null || origin == null) {
                throw new IllegalStateException("NodeDNSService not configured. Call configure() first.");
            }

            // Pick free port
            try (ServerSocket ss = new ServerSocket(0)) {
                port = ss.getLocalPort();
            }

            ConsolePrinter.printInfo("[NodeDNS] Starting DNS server on " + bindIp + ":" + port);

            InMemoryDNSPersistence persistence = new InMemoryDNSPersistence();
            DNSHandler handler = new DNSHandler(persistence, origin);



            Thread serverThread = new Thread(() -> {
                try {
                    DNSServer.start();
                } catch (Exception e) {
                    ConsolePrinter.printFail("[NodeDNS] DNS WebServer crashed: " + e.getMessage());
                    e.printStackTrace();
                }
            }, "node-dns-server");

            serverThread.setDaemon(true);
            serverThread.start();

            client = new DNSClient(bindIp, port);

            started = true;
            ConsolePrinter.printSuccess("[NodeDNS] DNS WebServer & Client started");

        } catch (Exception e) {
            ConsolePrinter.printFail("[NodeDNS] Failed to start DNS services: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Global DNS Client
     */
    public static DNSClient getClient() {
        if (!started) {
            throw new IllegalStateException("NodeDNSService not started");
        }
        return client;
    }

    /**
     * Global DNS WebServer
     */
    public static DNSServer getServer() {
        if (!started) {
            throw new IllegalStateException("NodeDNSService not started");
        }
        return server;
    }

    /**
     * Stop DNS server
     */
    public static synchronized void stop() {
        try {
            if (server != null) {
                server.stop();
                ConsolePrinter.printInfo("[NodeDNS] DNS WebServer stopped");
            }
        } catch (Exception e) {
            ConsolePrinter.printWarning("[NodeDNS] Failed to stop DNS server: " + e.getMessage());
        } finally {
            started = false;
            server = null;
            client = null;
        }
    }

    /**
     * Returns the port DNS server is bound to
     */
    public static int getPort() {
        if (!started) {
            throw new IllegalStateException("NodeDNSService not started");
        }
        return port;
    }
}
