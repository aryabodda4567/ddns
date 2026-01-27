package org.ddns.tests;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.db.BlockDb;
import org.ddns.db.DNSDb;
import org.ddns.db.TransactionDb;
import org.ddns.dns.*;
import org.ddns.util.ConsolePrinter;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Improved RigorousDNSTest runner
 * <p>
 * Usage:
 * java org.ddns.tests.RigorousDNSTest [--external host:port] [--quiet] [--threads N]
 * <p>
 * If --external is provided, the runner will NOT start an in-process DNSServer and will target the given host:port.
 * If omitted, the runner will start DNSServer bound to 127.0.0.1 on an ephemeral port.
 * <p>
 * Outputs a JSON summary into ./test-results.json
 */
public class DNSTest {
    private static final AtomicInteger failures = new AtomicInteger(0);
    private static final String ORIGIN = "example.com.";
    private static final int NUM_A = 20;
    private static final int NUM_AAAA = 12;
    private static final int NUM_TXT = 12;
    // dynamic counter for total tests run
    private static final AtomicInteger totalTests = new AtomicInteger(0);
    private static final List<Map<String, Object>> testResults = Collections.synchronizedList(new ArrayList<>());
    private static int serverPort;

    private static DNSClient client;

    public static void main(String[] args) {
        ConsolePrinter.printInfo("=== Rigorous MANY-Test DNSServer Console Runner ===");
        Security.addProvider(new BouncyCastleProvider());
        DNSTest dnsTest = new DNSTest();
        dnsTest.test();
        System.exit(0);
        // CLI flags
        boolean useExternal = false;
        String externalHost = null;
        boolean quiet = false;
        int threads = 64;

        // parse args
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--external":
                    if (i + 1 < args.length) {
                        useExternal = true;
                        externalHost = args[++i];
                    } else {
                        ConsolePrinter.printFail("Missing value for --external");
                        System.exit(2);
                    }
                    break;
                case "--quiet":
                    quiet = true;
                    break;
                case "--threads":
                    if (i + 1 < args.length) {
                        threads = Integer.parseInt(args[++i]);
                    }
                    break;
                default:
                    ConsolePrinter.printWarning("Unknown arg: " + args[i]);
            }
        }

        if (quiet) {
            // Quiet mode: minimize info prints (we'll still print failures and final summary)
            ConsolePrinter.printInfo("Running in quiet mode (info suppressed).");
        }

        try {
            if (useExternal) {
                // externalHost expected in form host:port
                String[] parts = externalHost.split(":");
                if (parts.length != 2) {
                    ConsolePrinter.printFail("--external expects host:port");
                    System.exit(2);
                }
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);
                ConsolePrinter.printInfo("Using external DNSServer " + host + ":" + port);
                client = new DNSClient(host, port);
            } else {
                // start in-process server bound to 127.0.0.1 with ephemeral port
                try (ServerSocket ss = new ServerSocket(0)) {
                    serverPort = ss.getLocalPort();
                }

                InMemoryDNSPersistence persistence = new InMemoryDNSPersistence();
                DNSHandler handler = new DNSHandler(persistence, ORIGIN);


                Thread serverThread = new Thread(() -> {
                    try {
                        DNSServer.start();
                    } catch (Exception e) {
                        ConsolePrinter.printFail("WebServer failed to start: " + e);
                        e.printStackTrace();
                    }
                }, "dnstest-server-thread");
                serverThread.setDaemon(true);
                serverThread.start();

                // wait for server to accept connections (probe TCP)
                boolean listening = waitForPort("127.0.0.1", serverPort, 20, 150);
                if (!listening)
                    throw new IllegalStateException("Timed out waiting for DNSServer to listen on port " + serverPort);

                client = new DNSClient("127.0.0.1", serverPort);
            }

            // ensure dnsjava cache is clean
            Lookup.getDefaultCache(DClass.IN).clearCache();

            // RUN TESTS
            // 1) Create many A records
            maybeInfo(quiet, "\n--- Bulk CREATE A records (" + NUM_A + ") ---");
            for (int i = 1; i <= NUM_A; i++) {
                String host = String.format("a-host-%02d.%s", i, ORIGIN);
                String ip = "10.0.0." + i;
                Record resp = client.sendCommand(createBaseCommand("CREATE", host, "A", ip), ORIGIN);
                recordTest("Create A #" + i, resp != null && resp.rdataToString().contains("OK: created"), resp);
            }

            // 2) Create many AAAA records
            maybeInfo(quiet, "\n--- Bulk CREATE AAAA records (" + NUM_AAAA + ") ---");
            for (int i = 1; i <= NUM_AAAA; i++) {
                String host = String.format("aaaa-host-%02d.%s", i, ORIGIN);
                String ip6 = String.format("2001:db8::%x", i);
                Record resp = client.sendCommand(createBaseCommand("CREATE", host, "AAAA", ip6), ORIGIN);
                recordTest("Create AAAA #" + i, resp != null && resp.rdataToString().contains("OK: created"), resp);
            }

            // 3) Create many TXT records
            maybeInfo(quiet, "\n--- Bulk CREATE TXT records (" + NUM_TXT + ") ---");
            for (int i = 1; i <= NUM_TXT; i++) {
                String host = String.format("txt-host-%02d.%s", i, ORIGIN);
                String txt = "txt-value-" + i;
                Record resp = client.sendCommand(createBaseCommand("CREATE", host, "TXT", txt), ORIGIN);
                recordTest("Create TXT #" + i, resp != null && resp.rdataToString().contains("OK: created"), resp);
            }

            // 4) Duplicate create attempts
            maybeInfo(quiet, "\n--- Duplicate CREATE attempts (should fail) ---");
            for (int i = 1; i <= 5; i++) {
                String host = String.format("a-host-%02d.%s", i, ORIGIN);
                String ip = "10.0.0." + i;
                Record resp = client.sendCommand(createBaseCommand("CREATE", host, "A", ip), ORIGIN);
                recordTest("Duplicate Create A #" + i, resp != null && resp.rdataToString().contains("ERR: already exists"), resp);
            }

            // 5) Lookups for created A records
            maybeInfo(quiet, "\n--- Lookups for created A records ---");
            for (int i = 1; i <= NUM_A; i++) {
                String host = String.format("a-host-%02d.%s", i, ORIGIN);
                String expected = "10.0.0." + i;
                boolean ok = retry(() -> {
                    Lookup.getDefaultCache(DClass.IN).clearCache();
                    Record[] recs = client.lookup(host, Type.A);
                    return recs != null && recs.length == 1 && recs[0].rdataToString().equals(expected);
                }, 8, 80);
                recordTest("Lookup A #" + i, ok, ok ? null : ("Expected " + expected));
            }

            // 6) Lookups for AAAA records (loose)
            maybeInfo(quiet, "\n--- Lookups for created AAAA records ---");
            for (int i = 1; i <= NUM_AAAA; i++) {
                String host = String.format("aaaa-host-%02d.%s", i, ORIGIN);
                boolean ok = retry(() -> {
                    Lookup.getDefaultCache(DClass.IN).clearCache();
                    Record[] recs = client.lookup(host, Type.AAAA);
                    return recs != null && recs.length >= 1 && recs[0].rdataToString().contains("2001") && recs[0].rdataToString().contains("db8");
                }, 8, 80);
                recordTest("Lookup AAAA #" + i, ok, ok ? null : "Expected AAAA containing 2001:db8");
            }

            // 7) Lookups for TXT records
            maybeInfo(quiet, "\n--- Lookups for created TXT records ---");
            for (int i = 1; i <= NUM_TXT; i++) {
                String host = String.format("txt-host-%02d.%s", i, ORIGIN);
                String expected = "\"" + "txt-value-" + i + "\"";
                boolean ok = retry(() -> {
                    Lookup.getDefaultCache(DClass.IN).clearCache();
                    Record[] recs = client.lookup(host, Type.TXT);
                    return recs != null && recs.length == 1 && recs[0].rdataToString().equals(expected);
                }, 6, 100);
                recordTest("Lookup TXT #" + i, ok, ok ? null : ("Expected " + expected));
            }

            // 8) ANY lookup
            maybeInfo(quiet, "\n--- Create multi-type host and run ANY query ---");
            String multiHost = "multi-host.any." + ORIGIN;
            client.sendCommand(createBaseCommand("CREATE", multiHost, "A", "10.10.10.10"), ORIGIN);
            client.sendCommand(createBaseCommand("CREATE", multiHost, "TXT", "multi-txt"), ORIGIN);
            boolean anyOk = retry(() -> {
                Lookup.getDefaultCache(DClass.IN).clearCache();
                Record[] recs = client.lookup(multiHost, Type.ANY);
                return recs != null && recs.length >= 2;
            }, 6, 80);
            recordTest("ANY lookup returns multiple records", anyOk, anyOk ? null : "Expected >=2 records for ANY");

            // 9) PTR reverse tests
            maybeInfo(quiet, "\n--- PTR reverse records for subset of A hosts ---");
            for (int i = 1; i <= 8; i++) {
                String ip = "10.0.0." + (i + 100);
                String host = String.format("ptr-target-%02d.%s", i, ORIGIN);
                client.sendCommand(createBaseCommand("CREATE", host, "A", ip), ORIGIN);
                String[] parts = ip.split("\\.");
                String reverseName = parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0] + ".in-addr.arpa.";
                Record respPtr = client.sendCommand(createBaseCommand("CREATE", reverseName, "PTR", host), ORIGIN);
                recordTest("Create PTR for " + ip, respPtr != null && respPtr.rdataToString().contains("OK: created"), respPtr);
                boolean ptrOk = retry(() -> {
                    Lookup.getDefaultCache(DClass.IN).clearCache();
                    Record[] recs = client.reverseLookup(ip);
                    System.out.println(ip);
                    return recs != null && recs.length >= 1 && recs[0].rdataToString().equals(host);
                }, 6, 100);
                recordTest("Reverse lookup for " + ip, ptrOk, ptrOk ? null : ("Expected PTR target " + host));
            }

            // 10) Update subset of A records
            maybeInfo(quiet, "\n--- Update subset of A records ---");
            for (int i = 1; i <= 10; i++) {
                String host = String.format("a-host-%02d.%s", i, ORIGIN);
                String newIp = "10.1.1." + i;
                Record resp = client.sendCommand(createBaseCommand("UPDATE", host, "A", newIp), ORIGIN);
                recordTest("Update A #" + i, resp != null && resp.rdataToString().contains("OK: updated"), resp);
                boolean ok = retry(() -> {
                    Lookup.getDefaultCache(DClass.IN).clearCache();
                    Record[] recs = client.lookup(host, Type.A);
                    return recs != null && recs.length == 1 && recs[0].rdataToString().equals(newIp);
                }, 8, 80);
                recordTest("Verify Updated A #" + i, ok, ok ? null : ("Expected " + newIp));
            }

            // 11) Delete many created records
            maybeInfo(quiet, "\n--- Delete many created records (cleanup) ---");
            for (int i = 1; i <= 12; i++) {
                String host = String.format("a-host-%02d.%s", i, ORIGIN);
                String ip = (i <= 10) ? ("10.1.1." + i) : ("10.0.0." + i); // first 10 updated, rest original
                Record resp = client.sendCommand(createBaseCommand("DELETE", host, "A", ip), ORIGIN);
                recordTest("Delete A " + host, resp != null && resp.rdataToString().contains("OK: deleted"), resp);
            }

            // 12) Delete non-existent items
            maybeInfo(quiet, "\n--- Delete non-existent records (expected fails) ---");
            for (int i = 1; i <= 6; i++) {
                String host = String.format("no-such-host-%02d.%s", i, ORIGIN);
                Record resp = client.sendCommand(createBaseCommand("DELETE", host, "A", "192.0.2." + i), ORIGIN);
                recordTest("Delete non-existent " + host, resp != null && resp.rdataToString().contains("ERR: not found"), resp);
            }

            // 13) Malformed command tests
            maybeInfo(quiet, "\n--- Malformed commands / invalid args ---");
            Map<String, Object> bad = new HashMap<>();
            bad.put("name", "bad.example.com.");
            Record badResp = client.sendCommand(bad, ORIGIN);
            recordTest("Malformed command (no action)", badResp != null && badResp.rdataToString().toLowerCase().contains("err"), badResp);

            // 14) Many small TXT creates
            maybeInfo(quiet, "\n--- Many small TXT creates ---");
            for (int i = 1; i <= 8; i++) {
                String host = String.format("txt-burst-%02d.%s", i, ORIGIN);
                String txt = "burst-value-" + i;
                Record resp = client.sendCommand(createBaseCommand("CREATE", host, "TXT", txt), ORIGIN);
                recordTest("Create burst TXT #" + i, resp != null && resp.rdataToString().contains("OK: created"), resp);
            }

            // 15) Final random spot-checks
            maybeInfo(quiet, "\n--- Random spot-checks ---");
            runSpotCheck("multi-host.any." + ORIGIN, Type.ANY, true);
            runSpotCheck("txt-host-05." + ORIGIN, Type.TXT, true);
            runSpotCheck("a-host-50." + ORIGIN, Type.A, false);
            runSpotCheck("aaaa-host-03." + ORIGIN, Type.AAAA, true);

        } catch (Exception e) {
            ConsolePrinter.printFail("CRITICAL: test runner aborted - " + e);
            e.printStackTrace();
            failures.incrementAndGet();
        } finally {
            if (DNSServer.get() != null) {
                try {
                    DNSServer.stop();
                } catch (Exception ignored) {
                }
            }
            int failCount = failures.get();
            int total = totalTests.get();
            // write JSON summary
            writeJsonSummary(total, failCount, testResults);

            if (failCount == 0) {
                ConsolePrinter.printSuccess("\n=== ALL " + total + " TESTS PASSED ===");
            } else {
                ConsolePrinter.printFail("\n=== " + failCount + " TEST(S) FAILED out of " + total + " ===");
            }
            System.exit(failCount == 0 ? 0 : 2);
        }


    }

    // ---- helpers ----

    private static void recordTest(String testName, boolean success, Record rawRespIfAny) {
        totalTests.incrementAndGet();
        if (success) {
            ConsolePrinter.printSuccess("  [SUCCESS] " + testName);
        } else {
            ConsolePrinter.printFail("  [FAIL]    " + testName);
            ConsolePrinter.printFail("            Details: " + (rawRespIfAny != null ? rawRespIfAny.rdataToString() : "NULL"));
            failures.incrementAndGet();
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", testName);
        entry.put("success", success);
        entry.put("response", rawRespIfAny == null ? null : rawRespIfAny.rdataToString());
        testResults.add(entry);
    }

    private static void recordTest(String testName, boolean success, String failureDetails) {
        totalTests.incrementAndGet();
        if (success) {
            ConsolePrinter.printSuccess("  [SUCCESS] " + testName);
        } else {
            ConsolePrinter.printFail("  [FAIL]    " + testName);
            ConsolePrinter.printFail("            Details: " + failureDetails);
            failures.incrementAndGet();
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", testName);
        entry.put("success", success);
        entry.put("details", failureDetails);
        testResults.add(entry);
    }

    private static Map<String, Object> createBaseCommand(String action, String name, String type, String value) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("action", action);
        cmd.put("name", name);
        cmd.put("type", type);
        cmd.put("value", value);
        cmd.put("ttl", 300L);
        cmd.put("ownerBase64", "");
        cmd.put("transactionHash", "tx_massive_test");
        return cmd;
    }

    private static boolean retry(Check check, int attempts, long waitMs) throws InterruptedException {
        for (int i = 0; i < attempts; i++) {
            try {
                if (check.run()) return true;
            } catch (Exception ignored) {
            }
            Thread.sleep(waitMs);
        }
        return false;
    }

    private static void runSpotCheck(String host, int type, boolean expectExists) {
        try {
            Lookup.getDefaultCache(DClass.IN).clearCache();
            Record[] recs = client.lookup(host, type);
            boolean found = recs != null && recs.length > 0;
            recordTest("SpotCheck - " + host + " type=" + type, expectExists == found,
                    "Expected exists=" + expectExists + " but found=" + found);
        } catch (Exception e) {
            recordTest("SpotCheck - " + host + " type=" + type, false, "Exception: " + e.getMessage());
        }
    }

    private static boolean waitForPort(String host, int port, int attempts, long waitMs) {
        for (int i = 0; i < attempts; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), (int) waitMs);
                return true;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static void maybeInfo(boolean quiet, String msg) {
        if (!quiet) ConsolePrinter.printInfo(msg);
    }

    private static void writeJsonSummary(int total, int failures, List<Map<String, Object>> results) {
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", total);
            summary.put("failures", failures);
            summary.put("results", results);
            Path out = Path.of("test-results.json");
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
                pw.println(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(summary));
            }
            ConsolePrinter.printInfo("Wrote test summary to " + out.toAbsolutePath());
        } catch (Exception e) {
            ConsolePrinter.printWarning("Failed to write test summary: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean run() throws Exception;
    }


    public void test(){
        DNSServer.start();

//        DNSServer.get().create(
//                new DNSModel("example.com", RecordType.A, 300, "1.2.3.4", null, "txhash")
//        );

        List<DNSModel> res = DNSServer.get().lookup("example.com", RecordType.A);
        System.out.println(res);

      //  System.out.println(DNSServer.get().reverseLookup("1.2.3.4"));

//        DNSServer.get().delete("example.com", RecordType.A, "1.2.3.4");
        System.out.println(TransactionDb.getInstance().readTransactionByHash("bc2a07e338b189d7b811e755052d2eedc011285d41a378785bd7644f4cfa9d07"));
        System.out.println(BlockDb.getInstance().readBlockByHash("a79337c9c09fd7a68cec6a6105fe9fa8e1cf7edb2e62e311dba7eea9605ebd0f"));





    }

}
