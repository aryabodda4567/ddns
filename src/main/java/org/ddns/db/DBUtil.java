package org.ddns.db; // Or your preferred package

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.ddns.bc.PublicKeyAdapter; // Assuming adapters are in bc package
import org.ddns.bc.PrivateKeyAdapter;
import org.ddns.bc.SignatureUtil;
import org.ddns.chain.Names; // Your Names class
import org.ddns.chain.Role;
import org.ddns.chain.governance.Nomination;
import org.ddns.net.NodeConfig;
import org.ddns.util.ConsolePrinter;

import java.lang.reflect.Type;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.sql.*;
import java.util.*;

/**
 * Singleton class to manage all interactions with the SQLite database.
 * Replaces PersistentStorage with structured tables for configuration,
 * node list, and governance state.
 */
public class DBUtil {

    private static DBUtil instance;
    private final String dbUrl;

    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(PublicKey.class, new PublicKeyAdapter())
            .registerTypeAdapter(PrivateKey.class, new PrivateKeyAdapter())
            .create();

    // Private constructor for Singleton
    private DBUtil(String dbFileName) {
        this.dbUrl = "jdbc:sqlite:" + dbFileName;
        initializeDatabase();
    }

    /**
     * Gets the single instance of DBUtil.
     * @return The singleton instance.
     */
    public static synchronized DBUtil getInstance() {
        if (instance == null) {
            instance = new DBUtil("node_state.db"); // Use a dedicated DB file name
        }
        return instance;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    /**
     * Creates all necessary tables if they don't exist.
     */
    private void initializeDatabase() {
        // Simple Key-Value store for configuration
        String configStoreSql = """
            CREATE TABLE IF NOT EXISTS config_store (
                key TEXT PRIMARY KEY,
                value TEXT
            );""";

        // Table for known nodes (replaces AVAILABLE_NODES)
        String nodesSql = """
            CREATE TABLE IF NOT EXISTS nodes (
                ip TEXT PRIMARY KEY,
                role TEXT NOT NULL,
                public_key TEXT NOT NULL UNIQUE
            );""";
        // Index for faster lookups by public key (optional but good practice)
        String nodePubKeyIndex = "CREATE INDEX IF NOT EXISTS idx_node_pubkey ON nodes (public_key);";

        // Table for active nominations (replaces NOMINATIONS)
        String nominationsSql = """
            CREATE TABLE IF NOT EXISTS nominations (
                ip TEXT NOT NULL,
                public_key TEXT NOT NULL,
                nomination_type INTEGER NOT NULL,
                is_voted INTEGER DEFAULT 0,
                PRIMARY KEY (ip, nomination_type)
            );""";

        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(configStoreSql);
            stmt.execute(nodesSql);
            stmt.execute(nodePubKeyIndex);
            stmt.execute(nominationsSql);
            ConsolePrinter.printSuccess("[DBUtil] Database and tables initialized successfully.");
        } catch (SQLException e) {
            ConsolePrinter.printFail("[DBUtil] Error initializing database: " + e.getMessage());
        }
    }

    // --- Methods replacing PersistentStorage ---

    // --- Generic Key-Value Operations (for config_store) ---

    public synchronized void putString(String key, String value) {
        String sql = "INSERT OR REPLACE INTO config_store (key, value) VALUES (?, ?)";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            ConsolePrinter.printFail("[DBUtil] Error saving string '" + key + "': " + e.getMessage());
        }
    }

    public String getString(String key) {
        String sql = "SELECT value FROM config_store WHERE key = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("value");
            }
        } catch (SQLException e) {
            ConsolePrinter.printFail("[DBUtil] Error getting string '" + key + "': " + e.getMessage());
        }
        return null; // Return null if key not found or error
    }

    public synchronized void putInt(String key, int value) {
        putString(key, String.valueOf(value));
    }

    public int getInt(String key, int defaultValue) {
        String valueStr = getString(key);
        if (valueStr != null) {
            try {
                return Integer.parseInt(valueStr);
            } catch (NumberFormatException e) {
                ConsolePrinter.printWarning("[DBUtil] Invalid integer format for key '" + key + "'. Returning default.");
            }
        }
        return defaultValue;
    }
    public int getInt(String key) {
        return getInt(key, 0); // Default to 0 if not found or invalid
    }


    public synchronized void putLong(String key, long value) {
        putString(key, String.valueOf(value));
    }

    public long getLong(String key, long defaultValue) {
        String valueStr = getString(key);
        if (valueStr != null) {
            try {
                return Long.parseLong(valueStr);
            } catch (NumberFormatException e) {
                ConsolePrinter.printWarning("[DBUtil] Invalid long format for key '" + key + "'. Returning default.");
            }
        }
        return defaultValue;
    }

    public synchronized void delete(String key) {
        String sql = "DELETE FROM config_store WHERE key = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            ConsolePrinter.printFail("[DBUtil] Error deleting key '" + key + "': " + e.getMessage());
        }
    }

    // --- Specific Getters/Setters using config_store ---

    public void saveKeys(PublicKey pubKey, PrivateKey privKey) {
        putString(Names.PUBLIC_KEY, SignatureUtil.getStringFromKey(pubKey));
        putString(Names.PRIVATE_KEY, SignatureUtil.getStringFromKey(privKey));
    }

    public PublicKey getPublicKey() throws Exception {
        String keyStr = getString(Names.PUBLIC_KEY);
        if (keyStr == null) return null;
        return SignatureUtil.getPublicKeyFromString(keyStr);
    }

    public PrivateKey getPrivateKey() throws Exception {
        String keyStr = getString(Names.PRIVATE_KEY);
        if (keyStr == null) return null;
        return SignatureUtil.getPrivateKeyFromString(keyStr);
    }

    public void saveRole(Role role) {
        putString(Names.ROLE, role.name());
    }

    public Role getRole() {
        String roleStr = getString(Names.ROLE);
        try {
            return roleStr != null ? Role.valueOf(roleStr) : Role.NONE;
        } catch (IllegalArgumentException e) {
            return Role.NONE; // Handle invalid stored role
        }
    }

    public void saveBootstrapIp(String ip) {
        putString(Names.BOOTSTRAP_NODE_IP, ip);
    }

    public String getBootstrapIp() {
        return getString(Names.BOOTSTRAP_NODE_IP);
    }

    // --- Node List Operations (nodes table) ---

    public synchronized void saveOrUpdateNode(NodeConfig nodeConfig) {
        if (nodeConfig == null || nodeConfig.getIp() == null || nodeConfig.getPublicKey() == null) return;
        String sql = "INSERT OR REPLACE INTO nodes (ip, role, public_key) VALUES (?, ?, ?)";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, nodeConfig.getIp());
            pstmt.setString(2, nodeConfig.getRole().name());
            pstmt.setString(3, SignatureUtil.getStringFromKey(nodeConfig.getPublicKey()));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            ConsolePrinter.printFail("[DBUtil] Error saving/updating node '" + nodeConfig.getIp() + "': " + e.getMessage());
        }
    }

    public synchronized void addNodes(Set<NodeConfig> nodes) {
        if (nodes == null) return;
        for (NodeConfig node : nodes) {
            saveOrUpdateNode(node); // Use saveOrUpdate to handle potential existing entries
        }
        ConsolePrinter.printInfo("[DBUtil] Added/Updated " + nodes.size() + " nodes to the database.");
    }

    public Set<NodeConfig> getAllNodes() {
        Set<NodeConfig> nodes = new HashSet<>();
        String sql = "SELECT ip, role, public_key FROM nodes";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                try {
                    String ip = rs.getString("ip");
                    Role role = Role.valueOf(rs.getString("role"));
                    PublicKey pubKey = SignatureUtil.getPublicKeyFromString(rs.getString("public_key"));
                    nodes.add(new NodeConfig(ip, role, pubKey));
                } catch (Exception e) {
                    ConsolePrinter.printWarning("[DBUtil] Failed to load node record: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            ConsolePrinter.printFail("[DBUtil] Error getting all nodes: " + e.getMessage());
        }
        return nodes;
    }

    // --- Nomination Operations (nominations table) ---

    public synchronized void saveNominations(List<Nomination> nominations) {
        // Clear existing nominations first
        String deleteSql = "DELETE FROM nominations";
        String insertSql = "INSERT INTO nominations (ip, public_key, nomination_type, is_voted) VALUES (?, ?, ?, ?)";

        try (Connection conn = connect()) {
            conn.setAutoCommit(false); // Start transaction

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(deleteSql); // Clear old data
            }

            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                if (nominations != null) {
                    for (Nomination n : nominations) {
                        pstmt.setString(1, n.getIpAddress());
                        pstmt.setString(2, SignatureUtil.getStringFromKey(n.getPublicKey()));
                        pstmt.setInt(3, n.getNominationType());
                        pstmt.setInt(4, n.isVoted() ? 1 : 0);
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }
            }

            conn.commit(); // Commit transaction
        } catch (SQLException e) {
            ConsolePrinter.printFail("[DBUtil] Error saving nominations: " + e.getMessage());
            // Rollback happens automatically on close if commit failed
        }
    }

    public List<Nomination> getNominations() {
        List<Nomination> nominations = new ArrayList<>();
        String sql = "SELECT ip, public_key, nomination_type, is_voted FROM nominations";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                try {
                    String ip = rs.getString("ip");
                    PublicKey pubKey = SignatureUtil.getPublicKeyFromString(rs.getString("public_key"));
                    int type = rs.getInt("nomination_type");
                    boolean isVoted = rs.getInt("is_voted") == 1;

                    Nomination n = new Nomination(type, ip, pubKey);
                    n.setVoted(isVoted);
                    nominations.add(n);
                } catch (Exception e) {
                    ConsolePrinter.printWarning("[DBUtil] Failed to load nomination record: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            ConsolePrinter.printFail("[DBUtil] Error getting nominations: " + e.getMessage());
        }
        return nominations;
    }

    public synchronized void clearNominations() {
        String sql = "DELETE FROM nominations";
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            ConsolePrinter.printFail("[DBUtil] Error clearing nominations: " + e.getMessage());
        }
        // Also clear related config store entries
        delete(Names.VOTES);
        delete(Names.VOTES_REQUIRED);
        delete(Names.VOTING_INIT_TIME);
        delete(Names.VOTING_TIME_LIMIT);
    }
}