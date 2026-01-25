package org.ddns.db;

import org.ddns.bc.SignatureUtil;
import org.ddns.consensus.QueueNode;
import org.ddns.constants.Role;
import org.ddns.node.NodeConfig;
import org.ddns.util.ConsolePrinter;

import java.security.PublicKey;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

public final class BootstrapDB {

    private static volatile BootstrapDB instance;
    private final String dbUrl;

    private BootstrapDB() {
        this.dbUrl = "jdbc:sqlite:bootstrap.db";
        initialize();
    }

    public static BootstrapDB getInstance() {
        if (instance == null) {
            synchronized (BootstrapDB.class) {
                if (instance == null) {
                    instance = new BootstrapDB();
                }
            }
        }
        return instance;
    }

    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL;");
            s.execute("PRAGMA synchronous=NORMAL;");
            s.execute("PRAGMA busy_timeout=3000;");
        }
        return conn;
    }

    private void initialize() {
        String nodesTable = """
                    CREATE TABLE IF NOT EXISTS bootstrap_nodes (
                        ip TEXT PRIMARY KEY,
                        role TEXT NOT NULL,
                        public_key TEXT NOT NULL UNIQUE
                    );
                """;

        String configTable = """
                    CREATE TABLE IF NOT EXISTS bootstrap_config (
                        key TEXT PRIMARY KEY,
                        value TEXT
                    );
                """;
        String queueTable = """
    CREATE TABLE IF NOT EXISTS consensus_queue (
        sno INTEGER PRIMARY KEY,
        ip TEXT NOT NULL,
        role TEXT NOT NULL,
        public_key TEXT NOT NULL
    );
""";


        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(nodesTable);
            stmt.execute(configTable);
            stmt.execute(queueTable);

            ConsolePrinter.printSuccess("[BootstrapDB] bootstrap.db initialized.");
        } catch (SQLException e) {
            ConsolePrinter.printFail("[BootstrapDB] Init failed: " + e.getMessage());
        }
    }

    /*───────────────────────────────────────────────
       NODE STORE METHODS
    ───────────────────────────────────────────────*/

    public synchronized void saveNode(NodeConfig node) {
        if (node == null || node.getIp() == null) return;

        String sql = """
                    INSERT INTO bootstrap_nodes (ip, role, public_key)
                    VALUES (?, ?, ?)
                    ON CONFLICT(ip)  DO UPDATE SET role = excluded.role, public_key = excluded.public_key;
                """;

        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, node.getIp());
            pstmt.setString(2, node.getRole().name());
            pstmt.setString(3, SignatureUtil.getStringFromKey(node.getPublicKey()));
            pstmt.executeUpdate();
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] Save node failed: " + e.getMessage());
        }
    }

    public Set<NodeConfig> getAllNodes() {
        Set<NodeConfig> nodes = new HashSet<>();
        String sql = "SELECT ip, role, public_key FROM bootstrap_nodes";

        try (Connection conn = connect(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                try {
                    String ip = rs.getString("ip");
                    Role role = Role.valueOf(rs.getString("role"));
                    PublicKey pubKey = SignatureUtil.getPublicKeyFromString(rs.getString("public_key"));
                    nodes.add(new NodeConfig(ip, role, pubKey));
                } catch (Exception ignore) {
                }
            }
        } catch (SQLException e) {
            ConsolePrinter.printFail("[BootstrapDB] Fetch nodes failed: " + e.getMessage());
        }
        return nodes;
    }

    public synchronized void clearNodes() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM bootstrap_nodes");
        } catch (SQLException e) {
            ConsolePrinter.printFail("[BootstrapDB] Clear nodes failed: " + e.getMessage());
        }
    }

    public synchronized void deleteNode(PublicKey publicKey) {
        if (publicKey == null) return;

        String sql = "DELETE FROM bootstrap_nodes WHERE public_key = ?";

        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, SignatureUtil.getStringFromKey(publicKey));
            pstmt.executeUpdate();
            ConsolePrinter.printSuccess("[BootstrapDB] Node removed via public key");
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] Delete node by key failed: " + e.getMessage());
        }
    }

    // Updates a node identified by IP. Sets new role and public_key.
    public synchronized void updateNode(String ip, Role role, PublicKey publicKey) {
        if (ip == null || ip.isBlank() || role == null || publicKey == null) {
            ConsolePrinter.printWarning("[BootstrapDB] updateNode(ip, ...) skipped due to null/blank args");
            return;
        }

        String sql = "UPDATE bootstrap_nodes SET role = ?, public_key = ? WHERE ip = ?";

        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role.name());
            ps.setString(2, SignatureUtil.getStringFromKey(publicKey));
            ps.setString(3, ip);

            int rows = ps.executeUpdate();
            if (rows > 0) {
                ConsolePrinter.printSuccess("[BootstrapDB] Node updated by IP: " + ip);
            } else {
                ConsolePrinter.printWarning("[BootstrapDB] No node found with IP: " + ip);
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] updateNode by IP failed: " + e.getMessage());
        }
    }

    // Updates a node identified by PublicKey. Sets new role and ip.
    public synchronized void updateNode(PublicKey publicKey, Role role, String ip) {
        if (publicKey == null || role == null || ip == null || ip.isBlank()) {
            ConsolePrinter.printWarning("[BootstrapDB] updateNode(publicKey, ...) skipped due to null/blank args");
            return;
        }

        String sql = "UPDATE bootstrap_nodes SET role = ?, ip = ? WHERE public_key = ?";

        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role.name());
            ps.setString(2, ip);
            ps.setString(3, SignatureUtil.getStringFromKey(publicKey));

            int rows = ps.executeUpdate();
            if (rows > 0) {
                ConsolePrinter.printSuccess("[BootstrapDB] Node updated by PublicKey");
            } else {
                ConsolePrinter.printWarning("[BootstrapDB] No node found with provided PublicKey");
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] updateNode by PublicKey failed: " + e.getMessage());
        }
    }


    /**
     * Deletes a specific node by IP address.
     */
    public synchronized void deleteNode(String ip) {
        if (ip == null || ip.isBlank()) return;

        String sql = "DELETE FROM bootstrap_nodes WHERE ip = ?";

        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, ip);
            int rows = pstmt.executeUpdate();

            if (rows > 0) {
                ConsolePrinter.printSuccess("[BootstrapDB] Node removed: " + ip);
            } else {
                ConsolePrinter.printWarning("[BootstrapDB] No node found with IP: " + ip);
            }
        } catch (SQLException e) {
            ConsolePrinter.printFail("[BootstrapDB] Delete node failed: " + e.getMessage());
        }
    }



    /*───────────────────────────────────────────────
       KEY-VALUE CONFIG METHODS
    ───────────────────────────────────────────────*/

    public synchronized void putConfig(String key, String value) {
        String sql = """
                    INSERT OR REPLACE INTO bootstrap_config (key, value)
                    VALUES (?, ?);
                """;

        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            ConsolePrinter.printFail("[BootstrapDB] putConfig failed: " + e.getMessage());
        }
    }

    public String getConfig(String key) {
        String sql = "SELECT value FROM bootstrap_config WHERE key = ?";

        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (SQLException e) {
            ConsolePrinter.printFail("[BootstrapDB] getConfig failed: " + e.getMessage());
        }
        return null;
    }

    public synchronized void deleteConfig(String key) {
        String sql = "DELETE FROM bootstrap_config WHERE key = ?";

        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            ConsolePrinter.printFail("[BootstrapDB] deleteConfig failed: " + e.getMessage());
        }
    }

    public synchronized void clearConfig() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM bootstrap_config");
        } catch (SQLException e) {
            ConsolePrinter.printFail("[BootstrapDB] clearConfig failed: " + e.getMessage());
        }
    }

    /*───────────────────────────────────────────────
       DELETE DB FILE
    ───────────────────────────────────────────────*/

    public synchronized void dropDatabase() {
        try {
            java.io.File f = new java.io.File(dbUrl.replace("jdbc:sqlite:", ""));
            if (f.exists() && f.delete()) {
                ConsolePrinter.printSuccess("[BootstrapDB] bootstrap.db deleted.");
            } else {
                ConsolePrinter.printFail("[BootstrapDB] Failed to delete bootstrap.db.");
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] Drop DB error: " + e.getMessage());
        }
    }

    public synchronized void insertQueueNode(QueueNode node) {
        if (node == null || node.getNodeConfig() == null) return;

        String sql = """
        INSERT OR REPLACE INTO consensus_queue (sno, ip, role, public_key)
        VALUES (?, ?, ?, ?);
    """;

        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, node.getSno());
            ps.setString(2, node.getNodeConfig().getIp());
            ps.setString(3, node.getNodeConfig().getRole().name());
            ps.setString(4, SignatureUtil.getStringFromKey(node.getNodeConfig().getPublicKey()));
            ps.executeUpdate();
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] insertQueueNode failed: " + e.getMessage());
        }
    }

    public synchronized Set<QueueNode> getAllQueueNodes() {
        Set<QueueNode> set = new HashSet<>();

        String sql = "SELECT sno, ip, role, public_key FROM consensus_queue ORDER BY sno ASC";

        try (Connection conn = connect(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int sno = rs.getInt("sno");
                String ip = rs.getString("ip");
                Role role = Role.valueOf(rs.getString("role"));
                PublicKey pk = SignatureUtil.getPublicKeyFromString(rs.getString("public_key"));

                NodeConfig nc = new NodeConfig(ip, role, pk);
                set.add(new QueueNode(nc, sno));
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] getAllQueueNodes failed: " + e.getMessage());
        }

        return set;
    }
    public synchronized QueueNode getQueueNodeBySno(int sno) {
        String sql = "SELECT sno, ip, role, public_key FROM consensus_queue WHERE sno = ?";

        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sno);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                String ip = rs.getString("ip");
                Role role = Role.valueOf(rs.getString("role"));
                PublicKey pk = SignatureUtil.getPublicKeyFromString(rs.getString("public_key"));

                NodeConfig nc = new NodeConfig(ip, role, pk);
                return new QueueNode(nc, sno);
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] getQueueNodeBySno failed: " + e.getMessage());
        }

        return null;
    }

    public synchronized void deleteQueueNode(int sno) {
        String sql = "DELETE FROM consensus_queue WHERE sno = ?";

        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sno);
            ps.executeUpdate();
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] deleteQueueNode failed: " + e.getMessage());
        }
    }

    public synchronized void clearQueue() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM consensus_queue");
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] clearQueue failed: " + e.getMessage());
        }
    }
    public synchronized void updateQueueNode(QueueNode node) {
        if (node == null || node.getNodeConfig() == null) return;

        String sql = """
        UPDATE consensus_queue
        SET ip = ?, role = ?, public_key = ?
        WHERE sno = ?;
    """;

        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, node.getNodeConfig().getIp());
            ps.setString(2, node.getNodeConfig().getRole().name());
            ps.setString(3, SignatureUtil.getStringFromKey(node.getNodeConfig().getPublicKey()));
            ps.setInt(4, node.getSno());
            ps.executeUpdate();
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] updateQueueNode failed: " + e.getMessage());
        }
    }
    public synchronized int getNextQueueSequence() {
        String sql = "SELECT MAX(sno) AS max_sno FROM consensus_queue";

        try (Connection conn = connect(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                int max = rs.getInt("max_sno");
                if (rs.wasNull()) {
                    return 0; // table empty
                }
                return max + 1;
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[BootstrapDB] getNextQueueSequence failed: " + e.getMessage());
        }

        return 0;
    }





}
