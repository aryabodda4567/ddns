package org.ddns;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.bc.SignatureUtil;
import org.ddns.bc.Transaction;
import org.ddns.bc.TransactionType;
import org.ddns.bootstrap.BootstrapNode;
import org.ddns.chain.Wallet;
import org.ddns.consensus.ConsensusEngine;
import org.ddns.constants.ElectionType;
import org.ddns.constants.Role;
import org.ddns.db.*;
import org.ddns.governance.Election;
import org.ddns.governance.Nomination;
import org.ddns.net.NetworkManager;
import org.ddns.node.NodeConfig;
import org.ddns.node.NodesManager;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.NetworkUtility;
import org.ddns.util.TimeUtil;
import org.ddns.dns.DNSClient;
import org.xbill.DNS.Record;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.Console;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.util.*;

/**
 * Main application entrypoint for the dDNS node app.
 *
 * Features:
 * - Node/bootstrap selection and configuration
 * - Election create / view result / cast vote flow (with hashed password)
 * - NodesManager integration for fetch/add/promote/sync
 * - DNS CLI sub-menu (configure DNS client, create/lookup/reverse/send management commands)
 *
 * Notes:
 * - Sensitive inputs (private key, election password) are read from Console when available;
 *   fall back to visible Scanner input in IDEs.
 * - Election password is hashed (SHA-256) before being stored in DBUtil.
 */
public class Main {
    private NetworkManager networkManager;
    private BootstrapNode bootstrapNode;
    private Election election;
    private NodesManager nodesManager;
    private Scanner scanner;

    private DNSClient dnsClient = null;

    private static final String ELECTION_PASSWORD = "election_password"; // DB key

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

//        BootstrapDB.getInstance().saveNode(new NodeConfig(
//                NetworkUtility.getLocalIpAddress(),
//                Role.GENESIS,
//                Wallet.getKeyPair().getPublic()
//        ));

        // Print basic state (safe debug)
//        try{
//            BootstrapDB.getInstance().clearConfig();;
//            BootstrapDB.getInstance().dropDatabase();
  //      DBUtil.getInstance().deleteDatabaseFile();
//
//        }catch (Exception e){
//            e.printStackTrace();
//        }

//
        ConsolePrinter.printInfo("Bootstrap nodes: " + BootstrapDB.getInstance().getAllNodes());
        ConsolePrinter.printInfo("Local node: " + DBUtil.getInstance().getSelfNode());
        ConsolePrinter.printInfo("Known nodes: " + DBUtil.getInstance().getAllNodes());

        Main app = new Main();
        app.init();
        ConsolePrinter.printInfo("BootstrapNode bound to NetworkManager. Listeners running.");
        app.ui();
    }

    public void init() {
        // one Scanner for lifecycle
        scanner = new Scanner(System.in);

        networkManager = new NetworkManager();
        bootstrapNode = new BootstrapNode(networkManager);
        election = new Election(networkManager);
        nodesManager = new NodesManager(networkManager, election);
        networkManager.registerHandler(new ConsensusEngine());
        networkManager.startListeners();

        // Print short fingerprint for debug - avoid leaking private key
        try {
            String fp = SignatureUtil.getStringFromKey(Wallet.getKeyPair().getPrivate());
//            ConsolePrinter.printInfo("Local key fingerprint (short): " + (fp.length() > 40 ? fp.substring(0, 40) : fp))
            System.out.println(fp);
        } catch (Throwable ignored) {}
    }

    /**
     * Main UI loop: choose bootstrap / normal node / shutdown.
     */
    public void ui() {
        while (true) {
            ConsolePrinter.printInfo("\n1) Select bootstrap\n2) Select Normal Node\n3) Shutdown");
            int option = readInt("Choose option: ");

            switch (option) {
                case 1 -> bootstrap();
                case 2 -> node();
                case 3 -> {
                    shutdown();
                    return;
                }
                default -> ConsolePrinter.printWarning("Choose a correct option");
            }
        }
    }

    public void bootstrap() {
        ConsolePrinter.printInfo("This system is running as bootstrap node");
//        node();
    }

    /**
     * Node flow:
     * - configure self node (keys & bootstrap ip)
     * - fetch nodes from bootstrap
     * - if role NONE => create election
     * - enter menu loop
     */
    public void node() {
        try {
            configNode();
            ConsolePrinter.printInfo(" [Main] Self node configured ");
            ConsolePrinter.printInfo(" Details: " + DBUtil.getInstance().getSelfNode());
        } catch (Exception e) {
            ConsolePrinter.printWarning("[Main] failed to initiate self node configuration: " + e.getMessage());
            return;
        }

        try {
            nodesManager.createFetchRequest();
        } catch (Exception e) {
            ConsolePrinter.printFail(" [Main] Failed to initiate fetch request to Bootstrap node: " + e.getMessage());
            return;
        }

        TimeUtil.waitForSeconds(2);
        Role role = DBUtil.getInstance().getRole();
        if (role == null || role.equals(Role.NONE)) {
            createElection();
        }
        menu();
    }

    /**
     * Configure self node: bootstrap IP + private key -> save keys and set self node.
     * Uses Console when available (secure readPassword), otherwise falls back to Scanner.
     */
    private void configNode() throws Exception {
        Console console = System.console();
        char[] privateKeyChars = null;
        String privateKeyString = null;
        String bootstrapNodeIp;

        if (console != null) {
            TimeUtil.waitForSeconds(1);
            String inputIp = console.readLine("Enter bootstrap node ip (or hostname): ");
            if (inputIp == null || inputIp.trim().isEmpty()) throw new IllegalArgumentException("No bootstrap IP entered");
            bootstrapNodeIp = inputIp.trim();
            validateIpOrHost(bootstrapNodeIp);
            DBUtil.getInstance().saveBootstrapIp(bootstrapNodeIp);

            privateKeyChars = console.readPassword("Enter private key (PEM string): ");
            if (privateKeyChars == null || privateKeyChars.length == 0) throw new IllegalArgumentException("No private key entered");
            privateKeyString = String.valueOf(privateKeyChars);
        } else {
            ConsolePrinter.printInfo("Enter bootstrap node ip (or hostname): ");
            bootstrapNodeIp = readLine("");
            if (bootstrapNodeIp == null || bootstrapNodeIp.trim().isEmpty()) throw new IllegalArgumentException("No bootstrap IP entered");
            validateIpOrHost(bootstrapNodeIp);
            DBUtil.getInstance().saveBootstrapIp(bootstrapNodeIp);

            ConsolePrinter.printInfo("Enter private key (input will be visible): ");
            privateKeyString = readLine("");
            if (privateKeyString == null || privateKeyString.trim().isEmpty()) throw new IllegalArgumentException("No private key entered");
        }

        // Convert and save keys
        PrivateKey privateKey = SignatureUtil.getPrivateKeyFromString(privateKeyString);
        PublicKey publicKey = Wallet.getPublicKeyFromPrivateKey(privateKey);

        ConsolePrinter.printInfo("Public key: " + publicKey);
        DBUtil.getInstance().saveKeys(publicKey, privateKey);

        // set self node using local IP
        String localIp = NetworkUtility.getLocalIpAddress();
        DBUtil.getInstance().setSelfNode(new NodeConfig(localIp, Role.NONE, publicKey));

        // zero-out sensitive buffers
        if (privateKeyChars != null) Arrays.fill(privateKeyChars, '\0');
        privateKeyString = null;
    }

    private void validateIpOrHost(String ipOrHost) {
        try {
            InetAddress addr = InetAddress.getByName(ipOrHost);
            if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()) {
                ConsolePrinter.printWarning("Warning: bootstrap IP resolves to loopback/localhost: " + addr.getHostAddress());
            }
        } catch (UnknownHostException uhe) {
            throw new IllegalArgumentException("Bootstrap IP/host is invalid or unresolvable: " + ipOrHost, uhe);
        }
    }

    public void shutdown() {
        try {
            if (networkManager != null) networkManager.stop();
        } finally {
            try { if (scanner != null) scanner.close(); } catch (Exception ignored) {}
            ConsolePrinter.printInfo("[Main] Closing system");
        }
    }

    /* -------------------------
     * Helper utilities (I/O)
     * ------------------------- */

    private String readLine(String prompt) {
        if (prompt != null && !prompt.isEmpty()) ConsolePrinter.printInfo(prompt);
        try {
            String line = scanner.nextLine();
            return (line != null) ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private int readInt(String prompt) {
        while (true) {
            if (prompt != null && !prompt.isEmpty()) ConsolePrinter.printInfo(prompt);
            String line;
            try {
                line = scanner.nextLine();
            } catch (Exception e) {
                return -1;
            }
            if (line == null || line.trim().isEmpty()) {
                ConsolePrinter.printWarning("Please enter a number.");
                continue;
            }
            try {
                return Integer.parseInt(line.trim());
            } catch (NumberFormatException nfe) {
                ConsolePrinter.printWarning("Invalid number. Try again.");
            }
        }
    }

    private char[] readPasswordWithPrompt(String prompt) {
        Console console = System.console();
        if (prompt != null && !prompt.isEmpty()) ConsolePrinter.printInfo(prompt);
        if (console != null) {
            return console.readPassword();
        } else {
            String p = scanner.nextLine();
            return (p == null) ? new char[0] : p.toCharArray();
        }
    }

    /* -------------------------
     * Election flows
     * ------------------------- */

    public void createElection() {
        ConsolePrinter.printInfo("Create election for joining the node");

        char[] password = readPasswordWithPrompt("Create an election password: ");
        if (password == null || password.length == 0) {
            ConsolePrinter.printWarning("Empty password; aborting election creation.");
            return;
        }
        String hash = hashPassword(password);
        DBUtil.getInstance().putString(ELECTION_PASSWORD, hash);
        Arrays.fill(password, '\0');

        TimeUtil.waitForSeconds(1);

        String name = readLine("Enter node name: ");
        if (name.length() == 0 || name.length() > 128) {
            ConsolePrinter.printWarning("Invalid node name (empty or too long).");
            return;
        }

        int time = readInt("Enter time limit of the election (in minutes): ");
        if (time <= 0 || time > 60 * 24) {
            ConsolePrinter.printWarning("Invalid time limit. Must be between 1 and 1440 minutes.");
            return;
        }

        String description = readLine("Enter description: ");
        if (description.length() == 0 || description.length() > 1024) {
            ConsolePrinter.printWarning("Invalid description (empty or too long).");
            return;
        }

        election.createElection(ElectionType.JOIN, time, name, description);
    }

    public void electionResult() {
        ConsolePrinter.printInfo("Enter password to view result. Once result is viewed the election will be ended even if time is not over.\n");

        TimeUtil.waitForSeconds(2);
        while (true) {
            char[] pwToView = readPasswordWithPrompt("Password: ");
            if (pwToView == null || pwToView.length == 0) {
                ConsolePrinter.printWarning("Enter correct password");
                continue;
            }
            String storedHash = DBUtil.getInstance().getString(ELECTION_PASSWORD);
            String providedHash = hashPassword(pwToView);
            Arrays.fill(pwToView, '\0');

            if (storedHash == null || !storedHash.equals(providedHash)) {
                ConsolePrinter.printWarning("Wrong password");
                continue;
            }
            break;
        }

        boolean result = election.getResult(); // blocks until election end or manual reveal
        if (result) {
            ConsolePrinter.printSuccess("[Main] Network accepted the node");
            try {
                nodesManager.setupNormalNode();
            } catch (Exception e) {
                ConsolePrinter.printFail("[Main] Failed to initiate add node request: " + e.getMessage());
                return;
            }
            nodesManager.createSyncRequest();
        } else {
            ConsolePrinter.printFail("[Main] Network rejected the node");
            shutdown();
        }
    }

    /* -------------------------
     * Main menu after node configured
     * ------------------------- */

    private void menu() {
        // Try a sync first
        try { nodesManager.createSyncRequest(); } catch (Exception ignored) {}
        while (true) {
            ConsolePrinter.printInfo("\n1. View result\n2. Caste Vote\n3. DNS Options\n4. Exit");
            int option = readInt("");
            switch (option) {
                case 1 -> electionResult();
                case 2 -> casteVote();
                case 3 -> dnsOptions();
                case 4 -> { shutdown(); return; }
                default -> ConsolePrinter.printWarning("Invalid options");
            }
        }
    }

    private void casteVote() {
        List<Nomination> nominationList = List.of(election.getNominations().toArray(new Nomination[0]));
        if (nominationList.isEmpty()) {
            ConsolePrinter.printInfo("No nominations available to vote.");
            return;
        }

        int i = 0;
        for (Nomination n : nominationList) {
            printNominationPretty(n, i++);
        }

        int index = readInt("Enter the index of the nomination: ");
        if (index < 0 || index >= nominationList.size()) {
            ConsolePrinter.printWarning("Index out of range.");
            return;
        }

        try {
            election.casteVote(nominationList.get(index));
            ConsolePrinter.printInfo("Vote cast successfully.");
        } catch (Exception e) {
            ConsolePrinter.printFail("Failed to cast vote: " + e.getMessage());
        }
    }

    /* -------------------------
     * DNS CLI integration
     * ------------------------- */

    private void dnsOptions() {
        while (true) {
            ConsolePrinter.printInfo("\nDNS MENU\n1) Configure DNS server\n2) Create DNS record (CREATE)\n3) Lookup record\n4) Reverse lookup (PTR)\n5) Send raw management command\n6) Back");
            int opt = readInt("Choose DNS option: ");
            try {
                switch (opt) {
                    case 1 -> configureDnsClient();
                    case 2 -> dnsCreateRecord();
                    case 3 -> dnsLookup();
                    case 4 -> dnsReverseLookup();
                    case 5 -> dnsSendRawCommand();
                    case 6 -> { return; }
                    default -> ConsolePrinter.printWarning("Invalid DNS option");
                }
            } catch (Exception e) {
                ConsolePrinter.printFail("DNS operation failed: " + e.getMessage());
            }
        }
    }

    private void configureDnsClient() {
        String host = readLine("Enter DNS server IP or hostname (e.g. 127.0.0.1): ");
        int port = readInt("Enter DNS server port (e.g. 53): ");
        int timeout = readInt("Resolver timeout seconds (default 5): ");
        if (timeout <= 0) timeout = 5;
        int retries = readInt("Retry attempts (default 2): ");
        if (retries < 1) retries = 2;

        try {
            dnsClient = new DNSClient(host, port, timeout, retries);
            ConsolePrinter.printSuccess("DNS client configured for " + host + ":" + port);
        } catch (Exception e) {
            dnsClient = null;
            ConsolePrinter.printFail("Failed to create DNS client: " + e.getMessage());
        }
    }

    private void dnsCreateRecord() {
        if (dnsClient == null) {
            ConsolePrinter.printWarning("DNS client not configured. Configure DNS server first.");
            return;
        }

        String origin = readLine("Enter zone/origin (e.g. example.com.): ");
        String name = readLine("Enter full record name (e.g. a-host-01.example.com.): ");
        if (!name.endsWith(".")) name = name + ".";
        String typeStr = readLine("Enter record type (A, AAAA, TXT, MX, PTR, etc) [A]: ");
        if (typeStr == null || typeStr.isEmpty()) typeStr = "A";
        typeStr = typeStr.toUpperCase(Locale.ROOT);
        String value = readLine("Enter record value (IP for A/AAAA, text for TXT, target for PTR/CNAME): ");
        String ttlStr = readLine("Enter TTL in seconds [300]: ");
        long ttl;
        try { ttl = ttlStr.isEmpty() ? 300L : Long.parseLong(ttlStr); } catch (NumberFormatException e) { ConsolePrinter.printWarning("Invalid TTL, using default 300."); ttl = 300L; }
        String ownerBase64 = readLine("Enter ownerBase64 (or leave empty): ");
        String txHash = readLine("Enter transaction hash (or leave empty for default tx_cli): ");
        if (txHash.isEmpty()) txHash = "tx_cli";

        Map<String, Object> cmd = createDnsCommandMap("CREATE", name, typeStr, value, ttl, ownerBase64, txHash);
        try {
            Record resp = dnsClient.sendCommand(cmd, origin);
            if (resp == null) ConsolePrinter.printInfo("No answer returned from server.");
            else ConsolePrinter.printInfo("Server response: " + resp.rdataToString());
        } catch (Exception e) {
            ConsolePrinter.printFail("Create record failed: " + e.getMessage());
        }
    }

    private void dnsLookup() {
        if (dnsClient == null) { ConsolePrinter.printWarning("DNS client not configured. Configure DNS server first."); return; }
        String name = readLine("Enter domain name (e.g. a-host-01.example.com): ");
        String typeStr = readLine("Enter record type (A, AAAA, TXT, MX, ANY) [A]: ");
        if (typeStr == null || typeStr.isEmpty()) typeStr = "A";
        int type = Type.value(typeStr.toUpperCase(Locale.ROOT));
        try {
            Record[] rr = dnsClient.lookup(name, type);
            if (rr == null || rr.length == 0) { ConsolePrinter.printInfo("No records found."); return; }
            ConsolePrinter.printInfo("Lookup returned " + rr.length + " record(s):");
            for (Record r : rr) System.out.println(" - " + r);
        } catch (TextParseException tpe) {
            ConsolePrinter.printFail("Invalid name: " + tpe.getMessage());
        } catch (Exception e) {
            ConsolePrinter.printFail("Lookup failed: " + e.getMessage());
        }
    }

    private void dnsReverseLookup() {
        if (dnsClient == null) { ConsolePrinter.printWarning("DNS client not configured. Configure DNS server first."); return; }
        String ip = readLine("Enter IP address to reverse lookup (IPv4/IPv6): ");
        try {
            Record[] rr = dnsClient.reverseLookup(ip);
            if (rr == null || rr.length == 0) { ConsolePrinter.printInfo("No PTR records found."); return; }
            ConsolePrinter.printInfo("Reverse lookup returned " + rr.length + " record(s):");
            for (Record r : rr) System.out.println(" - " + r);
        } catch (Exception e) {
            ConsolePrinter.printFail("Reverse lookup failed: " + e.getMessage());
        }
    }

    private void dnsSendRawCommand() {
        if (dnsClient == null) { ConsolePrinter.printWarning("DNS client not configured. Configure DNS server first."); return; }
        String origin = readLine("Enter zone/origin (e.g. example.com.): ");
        String action = readLine("Enter action (CREATE / UPDATE / DELETE): ").toUpperCase(Locale.ROOT);
        String name = readLine("Enter full record name (e.g. a-host-01.example.com.): ");
        if (!name.endsWith(".")) name = name + ".";
        String typeStr = readLine("Enter record type (A, AAAA, TXT, MX, PTR, etc) [A]: ");
        if (typeStr == null || typeStr.isEmpty()) typeStr = "A";
        typeStr = typeStr.toUpperCase(Locale.ROOT);
        String value = readLine("Enter record value (IP for A/AAAA, text for TXT, target for PTR/CNAME): ");
        String ttlStr = readLine("Enter TTL in seconds [300]: ");
        long ttl;
        try { ttl = ttlStr.isEmpty() ? 300L : Long.parseLong(ttlStr); } catch (NumberFormatException e) { ConsolePrinter.printWarning("Invalid TTL, using default 300."); ttl = 300L; }
        String ownerBase64 = readLine("Enter ownerBase64 (or leave empty): ");
        String txHash = readLine("Enter transaction hash (or leave empty for default tx_cli_raw): ");
        if (txHash.isEmpty()) txHash = "tx_cli_raw";

        Map<String, Object> cmd = createDnsCommandMap(action, name, typeStr, value, ttl, ownerBase64, txHash);
        try {
            Record resp = dnsClient.sendCommand(cmd, origin);
            if (resp == null) ConsolePrinter.printInfo("No answer returned from server.");
            else ConsolePrinter.printInfo("Server response: " + resp.rdataToString());
        } catch (Exception e) {
            ConsolePrinter.printFail("sendCommand failed: " + e.getMessage());
        }
    }

    private Map<String, Object> createDnsCommandMap(String action, String name, String type, String value, long ttl, String ownerBase64, String transactionHash) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("action", action);
        cmd.put("name", name);
        cmd.put("type", type);
        cmd.put("value", value);
        cmd.put("ttl", ttl);
        cmd.put("ownerBase64", ownerBase64 == null ? "" : ownerBase64);
        cmd.put("transactionHash", transactionHash == null ? "tx_cli" : transactionHash);
        return cmd;
    }

    /* -------------------------
     * Pretty printing, helpers
     * ------------------------- */

    public static void printNominationPretty(Nomination n) { printNominationPretty(n, -1); }

    public static void printNominationPretty(Nomination n, int index) {
        String border = "┌" + "─".repeat(58) + "┐";
        String midBorder = "├" + "─".repeat(58) + "┤";
        String bottom = "└" + "─".repeat(58) + "┘";

        NodeConfig cfg = n.getNodeConfig();

        System.out.println(border);
        System.out.println("│                     NOMINATION DETAILS                   │");
        System.out.println(midBorder);
        if (index >= 0) printKV("Index", String.valueOf(index));
        printKV("Node IP", safe(cfg.getIp()));
        printKV("Role", safe(cfg.getRole().toString()));
        printKV("Vote Casted", String.valueOf(n.getVote()));
        printKV("Start Time", String.valueOf(n.getStartTime()));
        printKV("Expire Time", String.valueOf(n.getExpireTime()));
        printKV("Election Type", String.valueOf(n.getElectionType()));
        printKV("Node Name", safe(n.getNodeName()));
        printKV("Description", safe(n.getDescription()));
        System.out.println(bottom);
    }

    private static void printKV(String key, String value) {
        System.out.printf("│ %-14s: %-40s │%n", key, value);
    }

    private static String safe(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() > 40) return s.substring(0, 37) + "...";
        return s;
    }

    private static String hashPassword(char[] password) {
        try {
            byte[] bytes = new String(password).getBytes("UTF-8");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            Arrays.fill(bytes, (byte) 0);
            return bytesToHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public static void testTransaction(){
        Transaction transaction = new Transaction(
                Wallet.getKeyPair().getPublic(),
                TransactionType.REGISTER,
                new ArrayList<>()
        );
        ConsensusEngine consensusEngine = new ConsensusEngine();
        consensusEngine.publishTransaction(transaction);
    }
}
