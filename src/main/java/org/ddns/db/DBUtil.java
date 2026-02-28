package org.ddns.db; // Or your preferred package

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ddns.bc.PrivateKeyAdapter;
import org.ddns.bc.PublicKeyAdapter;
import org.ddns.bc.SignatureUtil;
import org.ddns.consensus.CircularQueue;
import org.ddns.consensus.QueueNode;
import org.ddns.constants.ConfigKey;
import org.ddns.constants.FileNames;
import org.ddns.constants.Role;
import org.ddns.node.NodeConfig;
import org.ddns.util.ConversionUtil;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Singleton class to manage all interactions with the SQLite database.
 * Thread-safe for multi-threaded access: readers can run concurrently,
 * writes are exclusive and transactional. Uses SQLite WAL mode and a busy timeout.
 */
public class DBUtil {

    private static final Logger log = LoggerFactory.getLogger(DBUtil.class);

    // --- JSON for adapters (kept for future use if needed) ---
    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(PublicKey.class, new PublicKeyAdapter())
            .registerTypeAdapter(PrivateKey.class, new PrivateKeyAdapter())
            .create();
    // --- Singleton ---
    private static volatile DBUtil instance;
    private final String dbUrl;
    // --- Concurrency primitives ---
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true); // fair lock

    // --- Private constructor for Singleton ---
    private DBUtil(String dbFileName) {
        this.dbUrl = "jdbc:sqlite:" + dbFileName;
        // Initialize schema once, under the write lock
        withWrite(this::initializeDatabase);
    }

    /**
     * Gets the single instance of DBUtil.
     *
     * @return The singleton instance.
     */
    public static DBUtil getInstance() {
        DBUtil local = instance;
        if (local == null) {
            synchronized (DBUtil.class) {
                local = instance;
                if (local == null) {
                    local = new DBUtil(FileNames.NODE_UTILITY_FILE); // Utility file
                    instance = local;
                }
            }
        }
        return local;
    }

    private <T> T withRead(SQLCallable<T> body) {
        rwLock.readLock().lock();
        try {
            return body.call();
        } catch (Exception e) {
            throw wrap(e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private void withWrite(SQLRunnable body) {
        rwLock.writeLock().lock();
        try {
            body.run();
        } catch (Exception e) {
            throw wrap(e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private RuntimeException wrap(Exception e) {
        log.error("[DBUtil] " + e.getMessage());
        return (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
    }

    // --- Connection management with concurrency-friendly PRAGMAs ---
    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL;");   // better read/write concurrency
            s.execute("PRAGMA synchronous=NORMAL;"); // good durability-performance tradeoff in WAL
            s.execute("PRAGMA foreign_keys=ON;");
            s.execute("PRAGMA busy_timeout=5000;");  // wait up to 5s if locked
        }
        return conn;
    }

    /**
     * Creates all necessary tables if they don't exist.
     * Runs inside write lock from the constructor.
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
            log.info("[DBUtil] Database and tables initialized successfully.");
        } catch (SQLException e) {
            log.error("[DBUtil] Error initializing database: " + e.getMessage());
        }
    }

    public void putString(String key, String value) {
        withWrite(() -> {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT OR REPLACE INTO config_store (key, value) VALUES (?, ?)")) {
                    pstmt.setString(1, key);
                    pstmt.setString(2, value);
                    pstmt.executeUpdate();
                }
                conn.commit();
            }
        });
    }

    public String getString(String key) {
        return withRead(() -> {
            String sql = "SELECT value FROM config_store WHERE key = ?";
            try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, key);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next() ? rs.getString("value") : null;
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Generic Key-Value Operations (config_store)
    // -------------------------------------------------------------------------

    public void putInt(String key, int value) {
        putString(key, String.valueOf(value));
    }

    public int getInt(String key, int defaultValue) {
        String valueStr = getString(key);
        if (valueStr != null) {
            try {
                return Integer.parseInt(valueStr);
            } catch (NumberFormatException e) {
                log.warn("[DBUtil] Invalid integer format for key '" + key + "'. Returning default.");
            }
        }
        return defaultValue;
    }

    public int getInt(String key) {
        return getInt(key, 0); // Default to 0 if not found or invalid
    }

    public void putLong(String key, long value) {
        putString(key, String.valueOf(value));
    }

    public long getLong(String key, long defaultValue) {
        String valueStr = getString(key);
        if (valueStr != null) {
            try {
                return Long.parseLong(valueStr);
            } catch (NumberFormatException e) {
                log.warn("[DBUtil] Invalid long format for key '" + key + "'. Returning default.");
            }
        }
        return defaultValue;
    }

    public void delete(String key) {
        withWrite(() -> {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM config_store WHERE key = ?")) {
                    pstmt.setString(1, key);
                    pstmt.executeUpdate();
                }
                conn.commit();
            }
        });
    }

    public void saveKeys(PublicKey pubKey, PrivateKey privKey) {
        withWrite(() -> {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT OR REPLACE INTO config_store (key, value) VALUES (?, ?)")) {
                    pstmt.setString(1, ConfigKey.PUBLIC_KEY.key());
                    pstmt.setString(2, SignatureUtil.getStringFromKey(pubKey));
                    pstmt.addBatch();

                    pstmt.setString(1, ConfigKey.PRIVATE_KEY.key());
                    pstmt.setString(2, SignatureUtil.getStringFromKey(privKey));
                    pstmt.addBatch();

                    pstmt.executeBatch();
                }
                conn.commit();
            }
        });
    }

    public PublicKey getPublicKey() throws Exception {
        String keyStr = getString(ConfigKey.PUBLIC_KEY.key());
        if (keyStr == null) return null;
        return SignatureUtil.getPublicKeyFromString(keyStr);
    }

    // -------------------------------------------------------------------------
    // Specific Getters/Setters using config_store
    // -------------------------------------------------------------------------

    public PrivateKey getPrivateKey() throws Exception {
        String keyStr = getString(ConfigKey.PRIVATE_KEY.key());
        if (keyStr == null) return null;
        return SignatureUtil.getPrivateKeyFromString(keyStr);
    }

    public void saveRole(Role role) {
        putString(ConfigKey.ROLE.key(), role.name());
    }

    public Role getRole() {
        String roleStr = getString(ConfigKey.ROLE.key());
        try {
            return roleStr != null ? Role.valueOf(roleStr) : Role.NONE;
        } catch (IllegalArgumentException e) {
            return Role.NONE; // Handle invalid stored role
        }
    }

    public void saveBootstrapIp(String ip) {
        putString(ConfigKey.BOOTSTRAP_NODE_IP.key(), ip);
    }

    public String getBootstrapIp() {
        return getString(ConfigKey.BOOTSTRAP_NODE_IP.key());
    }

    public void saveOrUpdateNode(NodeConfig nodeConfig) {
        if (nodeConfig == null || nodeConfig.getIp() == null || nodeConfig.getPublicKey() == null) return;

        withWrite(() -> {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO nodes (ip, role, public_key) VALUES (?, ?, ?) " +
                                "ON CONFLICT(ip) DO UPDATE SET role=excluded.role, public_key=excluded.public_key")) {
                    pstmt.setString(1, nodeConfig.getIp());
                    pstmt.setString(2, nodeConfig.getRole().name());
                    pstmt.setString(3, SignatureUtil.getStringFromKey(nodeConfig.getPublicKey()));
                    pstmt.executeUpdate();
                }
                conn.commit();
            }
        });
    }

    public void addNodes(Set<NodeConfig> nodes) {
        if (nodes == null || nodes.isEmpty()) return;

        withWrite(() -> {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO nodes (ip, role, public_key) VALUES (?, ?, ?) " +
                                "ON CONFLICT(ip) DO UPDATE SET role=excluded.role, public_key=excluded.public_key")) {
                    int count = 0;
                    for (NodeConfig node : nodes) {
                        if (node == null || node.getIp() == null || node.getPublicKey() == null) continue;
                        pstmt.setString(1, node.getIp());
                        pstmt.setString(2, node.getRole().name());
                        pstmt.setString(3, SignatureUtil.getStringFromKey(node.getPublicKey()));
                        pstmt.addBatch();
                        count++;
                    }
                    if (count > 0) pstmt.executeBatch();
                    log.info("[DBUtil] Added/Updated " + count + " nodes to the database.");
                }
                conn.commit();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Node List Operations (nodes table)
    // -------------------------------------------------------------------------

    public void addNode(NodeConfig node) {
        if (node == null) return;
//        Update RR Queue
        System.out.println(CircularQueue.getInstance().size());
        CircularQueue.getInstance().addNode(new QueueNode(node,
                CircularQueue.getInstance().size()+1));
        System.out.println(CircularQueue.getInstance().size());

        withWrite(() -> {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO nodes (ip, role, public_key) VALUES (?, ?, ?) " +
                                "ON CONFLICT(ip) DO UPDATE SET role=excluded.role, public_key=excluded.public_key")) {
                    int count = 0;

                    pstmt.setString(1, node.getIp());
                    pstmt.setString(2, node.getRole().name());
                    pstmt.setString(3, SignatureUtil.getStringFromKey(node.getPublicKey()));
                    pstmt.addBatch();
                    count++;

                    pstmt.executeBatch();
                    log.info("[DBUtil] Added/Updated " + count + " nodes to the database.");
                }
                conn.commit();
            }
        });
    }

    /**
     * Delete a node by public key (string-encoded). Returns true if a row was deleted.
     */
    public boolean deleteNode(String publicKeyStr) {
        if (publicKeyStr == null) return false;
        final boolean[] ok = {false};
        withWrite(() -> {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM nodes WHERE public_key = ?")) {
                    ps.setString(1, publicKeyStr);
                    ok[0] = ps.executeUpdate() > 0;
                }
                conn.commit();
            }
        });
        return ok[0];
    }

    /**
     * Delete a node by PublicKey. Returns true if a row was deleted.
     */
    public boolean deleteNode(PublicKey publicKey) {
        try {
            return deleteNode(SignatureUtil.getStringFromKey(publicKey));
        } catch (Exception e) {
            log.error("[DBUtil] deleteNode(publicKey) error: " + e.getMessage());
            return false;
        }
    }

// --- Node delete/update operations ---

    /**
     * Optional: delete a node by IP (primary key).
     */
    public boolean deleteNodeByIp(String ip) {
        if (ip == null) return false;
        final boolean[] ok = {false};
        withWrite(() -> {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM nodes WHERE ip = ?")) {
                    ps.setString(1, ip);
                    ok[0] = ps.executeUpdate() > 0;
                }
                conn.commit();
            }
        });
        return ok[0];
    }

    /**
     * Update a node (found by public_key) to a new role and/or IP.
     * Any null parameter will keep the existing value.
     * Returns true if a row was updated.
     */
    public boolean updateNode(String publicKeyStr, Role newRole, String newIp) {
        if (publicKeyStr == null) return false;

        final boolean[] ok = {false};
        withWrite(() -> {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);

                // Read current values
                String sel = "SELECT ip, role FROM nodes WHERE public_key = ?";
                String curIp = null, curRole = null;
                try (PreparedStatement s = conn.prepareStatement(sel)) {
                    s.setString(1, publicKeyStr);
                    try (ResultSet rs = s.executeQuery()) {
                        if (rs.next()) {
                            curIp = rs.getString("ip");
                            curRole = rs.getString("role");
                        }
                    }
                }
                if (curIp == null) {
                    // Node not found
                    conn.rollback();
                    return;
                }

                String finalIp = newIp != null ? newIp : curIp;
                String finalRole = newRole != null ? newRole.name() : curRole;

                // Because ip is PRIMARY KEY, this UPDATE may fail if `finalIp` collides.
                // That’s intended; we surface it via the boolean return.
                try (PreparedStatement u = conn.prepareStatement(
                        "UPDATE nodes SET ip = ?, role = ? WHERE public_key = ?")) {
                    u.setString(1, finalIp);
                    u.setString(2, finalRole);
                    u.setString(3, publicKeyStr);
                    ok[0] = u.executeUpdate() > 0;
                }

                conn.commit();
            } catch (SQLException e) {
                log.error("[DBUtil] updateNode error: " + e.getMessage());
                // write lock scope will release; update returns false on error
            }
        });
        return ok[0];
    }

    /**
     * Overload: update by PublicKey object
     */
    public boolean updateNode(PublicKey publicKey, Role newRole, String newIp) {
        try {
            return updateNode(SignatureUtil.getStringFromKey(publicKey), newRole, newIp);
        } catch (Exception e) {
            log.error("[DBUtil] updateNode(publicKey) error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Convenience: update only role (by public key).
     */
    public boolean updateNodeRole(String ip, Role newRole) {
        return updateNode(ip, newRole, null);
    }

    /**
     * Convenience: update only IP (by public key).
     */
    public boolean updateNodeIp(String publicKeyStr, String newIp) {
        return updateNode(publicKeyStr, null, newIp);
    }

    public Set<NodeConfig> getAllNodes() {
        return withRead(() -> {
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
                        log.warn("[DBUtil] Failed to load node record: " + e.getMessage());
                    }
                }
            } catch (SQLException e) {
                log.error("[DBUtil] Error getting all nodes: " + e.getMessage());
            }
            return nodes;
        });
    }

    public NodeConfig getSelfNode() {
        String jsonString = this.getString(ConfigKey.SELF_NODE.key());
        return ConversionUtil.fromJson(jsonString, NodeConfig.class);
    }

    public void setSelfNode(NodeConfig nodeConfig) {
        this.putString(ConfigKey.SELF_NODE.key(), ConversionUtil.toJson(nodeConfig));
    }

    public void clearNominations() {
        withWrite(() -> {
            try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
                conn.setAutoCommit(false);
                stmt.execute("DELETE FROM nominations");
                conn.commit();
            } catch (SQLException e) {
                log.error("[DBUtil] Error clearing nominations: " + e.getMessage());
            }
            // Also clear related config store entries atomically
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "DELETE FROM config_store WHERE key IN (?, ?, ?, ?)")) {
                    pstmt.setString(1, ConfigKey.VOTES.key());
                    pstmt.setString(2, ConfigKey.VOTES_REQUIRED.key());
                    pstmt.setString(3, ConfigKey.VOTING_INIT_TIME.key());
                    pstmt.setString(4, ConfigKey.VOTING_TIME_LIMIT.key());
                    pstmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                log.error("[DBUtil] Error clearing related config keys: " + e.getMessage());
            }
        });
    }

    /**
     * Deletes all stored data from every table.
     * Keeps the database schema intact.
     */
    public void clearAllStorage() {
        withWrite(() -> {
            try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
                conn.setAutoCommit(false);
                stmt.executeUpdate("DELETE FROM config_store;");
                stmt.executeUpdate("DELETE FROM nodes;");
                stmt.executeUpdate("DELETE FROM nominations;");
                // Reset AUTOINCREMENT counters if any
                stmt.executeUpdate("DELETE FROM sqlite_sequence WHERE name IN ('config_store','nodes','nominations');");
                conn.commit();
                log.info("[DBUtil] All stored data cleared successfully.");
            } catch (SQLException e) {
                log.error("[DBUtil] Error clearing all storage: " + e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Nomination Operations (nominations table)
    // -------------------------------------------------------------------------

//    public void saveNominations(List<Nomination> nominations) {
//        withWrite(() -> {
//            try (Connection conn = connect()) {
//                conn.setAutoCommit(false);
//
//                try (Statement stmt = conn.createStatement()) {
//                    stmt.execute("DELETE FROM nominations");
//                }
//
//                if (nominations != null && !nominations.isEmpty()) {
//                    try (PreparedStatement pstmt = conn.prepareStatement(
//                            "INSERT INTO nominations (ip, public_key, nomination_type, is_voted) VALUES (?, ?, ?, ?)")) {
//                        for (Nomination n : nominations) {
//                            pstmt.setString(1, n.getIpAddress());
//                            pstmt.setString(2, SignatureUtil.getStringFromKey(n.getPublicKey()));
//                            pstmt.setInt(3, n.getNominationType());
//                            pstmt.setInt(4, n.isVoted() ? 1 : 0);
//                            pstmt.addBatch();
//                        }
//                        pstmt.executeBatch();
//                    }
//                }
//
//                conn.commit();
//            }
//        });
//    }
//
//    public List<Nomination> getNominations() {
//        return withRead(() -> {
//            List<Nomination> nominations = new ArrayList<>();
//            String sql = "SELECT ip, public_key, nomination_type, is_voted FROM nominations";
//            try (Connection conn = connect();
//                 Statement stmt = conn.createStatement();
//                 ResultSet rs = stmt.executeQuery(sql)) {
//                while (rs.next()) {
//                    try {
//                        String ip = rs.getString("ip");
//                        PublicKey pubKey = SignatureUtil.getPublicKeyFromString(rs.getString("public_key"));
//                        int type = rs.getInt("nomination_type");
//                        boolean isVoted = rs.getInt("is_voted") == 1;
//
//                        Nomination n = new Nomination(type, ip, pubKey);
//                        n.setVoted(isVoted);
//                        nominations.add(n);
//                    } catch (Exception e) {
//                        log.warn("[DBUtil] Failed to load nomination record: " + e.getMessage());
//                    }
//                }
//            } catch (SQLException e) {
//                log.error("[DBUtil] Error getting nominations: " + e.getMessage());
//            }
//            return nominations;
//        });
//    }

    /**
     * Deletes the entire SQLite database file.
     * Use with extreme caution — this cannot be undone.
     */
    public void deleteDatabaseFile() {
        withWrite(() -> {
            java.io.File dbFile = new java.io.File(dbUrl.replace("jdbc:sqlite:", ""));
            if (dbFile.exists() && dbFile.delete()) {
                log.info("[DBUtil] Database file deleted successfully.");
            } else {
                log.error("[DBUtil] Failed to delete database file or file not found.");
            }
        });
    }

    // --- Functional helpers for lock-scoped work ---
    @FunctionalInterface
    private interface SQLCallable<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    private interface SQLRunnable {
        void run() throws Exception;
    }
}
