package org.ddns.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import org.ddns.dns.DNSResolver; // custom resolver we’ll write
import org.xbill.DNS.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
public class UDPPacketServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(UDPPacketServer.class);

    private final DNSResolver resolver;
    private final int port;
    private volatile boolean running = true;
    private DatagramSocket socket;

    public UDPPacketServer(DNSResolver resolver, int port) {
        this.resolver = resolver;
        this.port = port;
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(port);
            log.info("UDP DNS listening on " + port);

            while (running) {
                byte[] buf = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                DNSExecutor.POOL.submit(() -> handle(packet));
            }
        } catch (Exception e) {
            if (running) e.printStackTrace();
        }
    }

    private void handle(DatagramPacket packet) {
        try {
            Message query = new Message(packet.getData());
            Message response = resolver.send(query);
            byte[] out = response.toWire();

            DatagramPacket reply = new DatagramPacket(out, out.length, packet.getSocketAddress());
            socket.send(reply);
        } catch (Exception ignored) {}
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
    }
}
