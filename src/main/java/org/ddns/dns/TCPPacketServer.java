package org.ddns.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.xbill.DNS.Message;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class TCPPacketServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TCPPacketServer.class);

    private final DNSResolver resolver;
    private final int port;
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    public TCPPacketServer(DNSResolver resolver, int port) {
        this.resolver = resolver;
        this.port = port;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            log.info("TCP DNS listening on " + port);

            while (running) {
                Socket client = serverSocket.accept();
                client.setSoTimeout(10_000);

                DNSExecutor.POOL.submit(() -> handle(client));
            }
        } catch (IOException e) {
            if (running) {
                log.error("[TCP DNS] Server error: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            DataInputStream dis = new DataInputStream(in);
            DataOutputStream dos = new DataOutputStream(out);

            while (running && !socket.isClosed()) {
                // ---- DNS TCP framing: 2 bytes length prefix ----
                int length;
                try {
                    length = dis.readUnsignedShort();
                } catch (EOFException eof) {
                    break; // client closed
                }

                if (length <= 0 || length > 65535) {
                    break;
                }

                byte[] data = dis.readNBytes(length);
                if (data.length != length) {
                    break;
                }

                Message query;
                try {
                    query = new Message(data);
                } catch (IOException parseFail) {
                    continue; // ignore malformed packet
                }

                Message response = resolver.send(query);
                byte[] outData = response.toWire();

                // ---- Write framed response ----
                dos.writeShort(outData.length);
                dos.write(outData);
                dos.flush();
            }

        } catch (Exception e) {
            // Silent close — normal in DNS
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }
}
