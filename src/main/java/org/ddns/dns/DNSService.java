package org.ddns.dns;

import org.ddns.util.ConsolePrinter;

public final class DNSService {

    private static UDPPacketServer udp;
    private static TCPPacketServer tcp;

    public static void start(int port) throws Exception {
        DNSResolver resolver = new DNSResolver();

        udp = new UDPPacketServer(resolver, port);
        tcp = new TCPPacketServer(resolver, port);

        Thread udpThread = new Thread(udp, "DNS-UDP");
        Thread tcpThread = new Thread(tcp, "DNS-TCP");

        udpThread.start();
        tcpThread.start();

        ConsolePrinter.printSuccess("DNS Service started on port " + port);
    }

    public static void stop() {
        if (udp != null) udp.stop();
        if (tcp != null) tcp.stop();
        DNSExecutor.POOL.shutdown();
    }
}
