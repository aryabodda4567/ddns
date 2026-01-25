package org.ddns.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.ddns.bc.Transaction;
import org.ddns.bc.TransactionType;
import org.ddns.db.DBUtil;
import org.ddns.db.DNSDb;
import org.ddns.dns.DNSModel;
import org.ddns.node.NodeDNSService;
import org.ddns.dns.DNSClient;
import org.xbill.DNS.Type;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class WebHttpServer {

    private static DNSClient dnsClient;

    public static void main(String[] args) throws Exception {

        start();

    }

    public static void start() throws Exception {

        // Use the shared DNS client from NodeDNSService
        dnsClient = NodeDNSService.getClient();

        if (dnsClient == null) {
            throw new IllegalStateException("DNSClient is not initialized. Start NodeDNSService first.");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // Static pages
        server.createContext("/register.html", ex -> serveFile(ex, "web/register.html"));
        server.createContext("/check.html", ex -> serveFile(ex, "web/check.html"));

        // API
        server.createContext("/api/register", WebHttpServer::handleRegister);
        server.createContext("/api/check", WebHttpServer::handleCheck);

        server.setExecutor(null);
        server.start();

        System.out.println("Web UI running at http://localhost:8080/register.html");
    }

    // ================= API =================

    private static void handleRegister(HttpExchange ex) throws IOException {
        try {
            enableCORS(ex);

            if (ex.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }

            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                send(ex, 405, "Method Not Allowed");
                return;
            }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);

            String domain = form.get("domain");
            String typeStr = form.get("type");
            String value = form.get("value");

            if (domain == null || typeStr == null || value == null ||
                    domain.isBlank() || typeStr.isBlank() || value.isBlank()) {
                send(ex, 400, "Missing fields");
                return;
            }

            // Convert type string to DNS Type
            int dnsType = switch (typeStr.toUpperCase()) {
                case "A" -> Type.A;
                case "AAAA" -> Type.AAAA;
                case "TXT" -> Type.TXT;
                case "PTR" -> Type.PTR;
                default -> throw new IllegalArgumentException("Unsupported DNS type: " + typeStr);
            };

            // 1. Create DNSModel
            DNSModel model = new DNSModel(
                    domain,
                    dnsType,
                    300,
                    value,
                    DBUtil.getInstance().getPublicKey(),  // 👈 self public key
                    null
            );

            // 2. Create Transaction
            Transaction tx = new Transaction(
                    DBUtil.getInstance().getPublicKey(),
                    TransactionType.REGISTER,
                    List.of(model)
            );

            // 3. Sign Transaction
            tx.sign(DBUtil.getInstance().getPrivateKey());

            // 4. Publish Transaction
            Transaction.publish(tx);

            // 5. Respond to user
            send(ex, 200,
                    "Transaction submitted successfully!\n" +
                            "TX Hash: " + tx.getHash());

        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "Server error: " + e.getMessage());
        }
    }


    private static void handleCheck(HttpExchange ex) throws IOException {
        try {
            enableCORS(ex);

            if (ex.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }

            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                send(ex, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());

            String domain = q.get("domain");
            String ip = q.get("ip");

            StringBuilder result = new StringBuilder();


            DNSDb dnsDb = org.ddns.db.DNSDb.getInstance();

            if (domain != null && !domain.isBlank()) {
               List<DNSModel> records = dnsDb.lookup(domain, -1); // -1 = ANY

                result.append("Domain lookup (from blockchain state):\n");

                if (records.isEmpty()) {
                    result.append("No records found\n");
                } else {
                    for (var r : records) {
                        result.append(r).append("\n");
                    }
                }
            }

            if (ip != null && !ip.isBlank()) {
                List<DNSModel> records = dnsDb.reverseLookup(ip);
                
                result.append("\nReverse lookup (from blockchain state):\n");

                if (records.isEmpty()) {
                    result.append("No records found\n");
                } else {
                    for (var r : records) {
                        result.append(r).append("\n");
                    }
                }
            }

            if (result.isEmpty()) {
                result.append("Provide domain or ip");
            }

            send(ex, 200, result.toString());

        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "Server error: " + e.getMessage());
        }
    }


    // ================= UTIL =================

    private static void serveFile(HttpExchange ex, String path) throws IOException {
        try {
            enableCORS(ex);

            InputStream is = WebHttpServer.class.getClassLoader().getResourceAsStream(path);
            if (is == null) {
                send(ex, 404, "Not Found: " + path);
                return;
            }

            byte[] data = is.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, data.length);
            ex.getResponseBody().write(data);
            ex.close();

        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "Server error: " + e.getMessage());
        }
    }

    private static void send(HttpExchange ex, int code, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    private static void enableCORS(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static Map<String, String> parseForm(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;

        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], "UTF-8"),
                        URLDecoder.decode(kv[1], "UTF-8"));
            }
        }
        return map;
    }

    private static Map<String, String> parseQuery(String q) throws UnsupportedEncodingException {
        if (q == null || q.isEmpty()) return Map.of();
        return parseForm(q);
    }
}
