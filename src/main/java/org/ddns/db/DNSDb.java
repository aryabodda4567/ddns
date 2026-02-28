package org.ddns.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.ddns.bc.SignatureUtil;
import org.ddns.constants.FileNames;
import org.ddns.dns.DNSModel;
import org.ddns.dns.DNSPersistence;
import org.ddns.util.TimeUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * SQLite-backed implementation of DNSPersistence.
 * <p>
 * Production-ready improvements included:
 * - atomic snapshot export using "VACUUM INTO" when supported (safer than raw file copy)
 * - fallback file copy if VACUUM INTO is unavailable
 * - helper to get DB file path
 * - export snapshot as Base64 (useful for transfer over HTTP/messages)
 * - truncateDatabase() that deletes all rows and optionally runs VACUUM to reclaim space
 * - detailed logging using ConsolePrinter
 * <p>
 * DB file: DNS_FILE (jdbc:sqlite:DNS_FILE)
 */
public final class DNSDb implements DNSPersistence {

    private static final Logger log = LoggerFactory.getLogger(DNSDb.class);

    private static volatile DNSDb instance;
    private final String dbUrl;

    private DNSDb() {
        this.dbUrl = "jdbc:sqlite:" + FileNames.DNS_DB;
        //  this.dbUrl="jdbc:sqlite:FileNames.DNS_SNAPSHOT";
        initialize();
    }

    public static DNSDb getInstance() {
        if (instance == null) {
            synchronized (DNSDb.class) {
                if (instance == null) instance = new DNSDb();
            }
        }
        return instance;
    }

    /**
     * Convert an object to an SQL literal appropriate for an INSERT statement:
     * - If obj == null -> "NULL"
     * - If obj is Integer/Long/Short/Byte/Double/Float/Number -> numeric literal (toString)
     * - Otherwise -> treated as String, escaped single quotes -> doubled and wrapped in single quotes
     * <p>
     * Note: This method is package-private to keep it simple and local to this class.
     */
    private static String toSqlValue(Object obj) {
        if (obj == null) return "NULL";

        if (obj instanceof Number) {
            // Numeric types - use toString (keeps integers and floats intact)
            return obj.toString();
        }

        // For everything else, treat as String and escape single quotes
        String s = obj.toString();
        // Replace single quote with two single quotes as per SQL escaping rules
        String escaped = s.replace("'", "''");
        return "'" + escaped + "'";
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

    // ---------------------------
    // Persistence API (methods) - unchanged
    // ---------------------------

    private void initialize() {
        String createTable = """
                    CREATE TABLE IF NOT EXISTS dns_records (
                        name TEXT NOT NULL,
                        name_norm TEXT NOT NULL,
                        type INTEGER NOT NULL,
                        ttl INTEGER NOT NULL,
                        rdata TEXT NOT NULL,
                        owner TEXT,
                        transaction_hash TEXT,
                        timestamp INTEGER,
                        PRIMARY KEY (name_norm, type)
                    );
                """;

        String idxName = "CREATE INDEX IF NOT EXISTS idx_dns_name_norm ON dns_records(name_norm);";
        String idxType = "CREATE INDEX IF NOT EXISTS idx_dns_type ON dns_records(type);";
        String idxRdata = "CREATE INDEX IF NOT EXISTS idx_dns_rdata ON dns_records(rdata);";
        String index ="CREATE INDEX IF NOT EXISTS idx_dns_name_type ON dns_records(name_norm, type);";


        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(createTable);
            stmt.execute(idxName);
            stmt.execute(idxType);
            stmt.execute(idxRdata);
            stmt.execute(index);

            // Migration: ensure timestamp column exists (for older DBs)
            boolean hasTimestamp = false;
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(dns_records);")) {
                while (rs.next()) {
                    String colName = rs.getString("name");
                    if ("timestamp".equalsIgnoreCase(colName)) {
                        hasTimestamp = true;
                        break;
                    }
                }
            } catch (SQLException ignore) {
            }

            if (!hasTimestamp) {
                try {
                    stmt.execute("ALTER TABLE dns_records ADD COLUMN timestamp INTEGER;");
                    log.info("[SqliteDNSPersistence] Added missing 'timestamp' column.");
                } catch (SQLException e) {
                    log.error("[SqliteDNSPersistence] Failed to add timestamp column: " + e.getMessage());
                }
            }

            log.info("[SqliteDNSPersistence] DNS_FILE initialized.");
        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] Init failed: " + e.getMessage());
        }
    }

    @Override
    public synchronized boolean addRecord(DNSModel record) {
        if (record == null || record.getName() == null || record.getRdata() == null) return false;
        String nameNorm = normalize(record.getName());
        String ownerStr = record.getOwner() == null ? null : SignatureUtil.getStringFromKey(record.getOwner());
        long ts = TimeUtil.getCurrentUnixTime();
        String sql = """
                    INSERT OR IGNORE INTO dns_records (name, name_norm, type, ttl, rdata, owner, transaction_hash, timestamp)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                """;

        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.getName());
            ps.setString(2, nameNorm);
            ps.setInt(3, record.getType());
            ps.setLong(4, record.getTtl());
            ps.setString(5, record.getRdata());
            ps.setString(6, ownerStr);
            ps.setString(7, record.getTransactionHash());
            ps.setLong(8, ts);
            int updated = ps.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] addRecord failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized boolean updateRecord(DNSModel record) {
        if (record == null || record.getName() == null) return false;

        String nameNorm = normalize(record.getName());
        String ownerStr = record.getOwner() == null ? null : SignatureUtil.getStringFromKey(record.getOwner());
        long ts = TimeUtil.getCurrentUnixTime();

        String updateSql = """
        UPDATE dns_records
        SET name = ?, ttl = ?, rdata = ?, owner = ?, transaction_hash = ?, timestamp = ?
        WHERE name_norm = ? AND type = ?;
    """;

        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, record.getName());
            ps.setLong(2, record.getTtl());
            ps.setString(3, record.getRdata());
            ps.setString(4, ownerStr);
            ps.setString(5, record.getTransactionHash());
            ps.setLong(6, ts);
            ps.setString(7, nameNorm);
            ps.setInt(8, record.getType());

            int rows = ps.executeUpdate();

            return rows > 0;

        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] updateRecord failed: " + e.getMessage());
            return false;
        }
    }


    @Override
    public synchronized boolean deleteRecord(String name, int type, String rdata) {
        if (name == null || rdata == null) return false;
        String nameNorm = normalize(name);
        String sql = "DELETE FROM dns_records WHERE name_norm = ? AND type = ? AND rdata = ?;";

        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nameNorm);
            ps.setInt(2, type);
            ps.setString(3, rdata);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] deleteRecord failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<DNSModel> lookup(String name, int type) {
        if (name == null) return List.of();
        String nameNorm = normalize(name);
        String sql;
        if (type == -1) {
            sql = "SELECT name, type, ttl, rdata, owner, transaction_hash, timestamp FROM dns_records WHERE name_norm = ?;";
        } else {
            sql = "SELECT name, type, ttl, rdata, owner, transaction_hash, timestamp FROM dns_records WHERE name_norm = ? AND type = ?;";
        }

        List<DNSModel> out = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nameNorm);
            if (type != -1) ps.setInt(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String n = rs.getString("name");
                    int t = rs.getInt("type");
                    long ttl = rs.getLong("ttl");
                    String rdata = rs.getString("rdata");
                    String ownerStr = rs.getString("owner");
                    String txHash = rs.getString("transaction_hash");
                    long ts = rs.getLong("timestamp");

                    PublicKey pub = null;
                    if (ownerStr != null) {
                        try {
                            pub = SignatureUtil.getPublicKeyFromString(ownerStr);
                        } catch (Exception ignore) {
                        }
                    }
                    DNSModel model = new DNSModel(n, t, ttl, rdata, pub, txHash,ts);

                    out.add(model);
                }
            }
        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] lookup failed: " + e.getMessage());
        }
        return out;
    }

    @Override
    public List<DNSModel> reverseLookup(String ipOrInAddr) {
        if (ipOrInAddr == null) return List.of();
        String nameNorm = normalize(ipOrInAddr);
        String sql = "SELECT name, type, ttl, rdata, owner, transaction_hash, timestamp FROM dns_records WHERE rdata = ?;";

        List<DNSModel> out = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nameNorm);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String n = rs.getString("name");
                    int t = rs.getInt("type");
                    long ttl = rs.getLong("ttl");
                    String rdata = rs.getString("rdata");
                    String ownerStr = rs.getString("owner");
                    String txHash = rs.getString("transaction_hash");
                    long ts = rs.getLong("timestamp");
                    PublicKey pub = null;
                    if (ownerStr != null) {
                        try {
                            pub = SignatureUtil.getPublicKeyFromString(ownerStr);
                        } catch (Exception ignore) {
                        }
                    }
                    DNSModel model = new DNSModel(n, t, ttl, rdata, pub, txHash,ts);

                    out.add(model);
                }
            }
        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] reverseLookup failed: " + e.getMessage());
        }
        return out;
    }

    @Override
    public List<DNSModel> listAll() {
        String sql = "SELECT name, type, ttl, rdata, owner, transaction_hash, timestamp FROM dns_records;";
        List<DNSModel> out = new ArrayList<>();
        try (Connection conn = connect(); Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                String n = rs.getString("name");
                int t = rs.getInt("type");
                long ttl = rs.getLong("ttl");
                String rdata = rs.getString("rdata");
                String ownerStr = rs.getString("owner");
                String txHash = rs.getString("transaction_hash");
                long ts = rs.getLong("timestamp");
                PublicKey pub = null;
                if (ownerStr != null) {
                    try {
                        pub = SignatureUtil.getPublicKeyFromString(ownerStr);
                    } catch (Exception ignore) {
                    }
                }
                DNSModel model = new DNSModel(n, t, ttl, rdata, pub, txHash,ts);

                out.add(model);
            }
        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] listAll failed: " + e.getMessage());
        }
        return out;
    }

    private String normalize(String name) {
        return name == null ? null : name.toLowerCase();
    }

    /**
     * Returns the absolute file-system path of the currently configured SQLite DB file.
     * This is the original DB file (not a snapshot).
     *
     * @return absolute path, or null if the file doesn't exist.
     */
    public synchronized String getDatabaseFilePath() {
        String originalPath = dbUrl.replace("jdbc:sqlite:", "");
        try {
            Path p = Path.of(originalPath).toAbsolutePath();
            if (Files.exists(p)) {
                return p.toString();
            } else {
                log.error("[SqliteDNSPersistence] DB file not found at: " + p);
                return null;
            }
        } catch (Exception e) {
            log.error("[SqliteDNSPersistence] getDatabaseFilePath failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Exports a snapshot of the database to snapshots directory with a timestamped filename.
     * <p>
     * Strategy:
     * 1) Try to use "VACUUM INTO 'path'" (atomic and consistent) — requires SQLite >= 3.27.
     * 2) If VACUUM INTO fails, fall back to a safe file copy (best-effort).
     *
     * @return absolute path to the snapshot file, or null on failure.
     */
    public synchronized String exportSnapshot() {
        String originalPath = dbUrl.replace("jdbc:sqlite:", "");
        Path snapshotDir = Path.of(FileNames.SNAPSHOT_DIR).toAbsolutePath();
        try {
            Files.createDirectories(snapshotDir);
        } catch (IOException e) {
            log.error("[SqliteDNSPersistence] Failed to create snapshot directory: " + e.getMessage());
            return null;
        }

        String timestamp = String.valueOf(TimeUtil.getCurrentUnixTime());
        String snapshotName = "dns_snapshot_" + timestamp + ".db";
        Path snapshotPath = snapshotDir.resolve(snapshotName);

        // First attempt: VACUUM INTO (creates a consistent copy while DB is online)
        try (Connection conn = connect(); Statement s = conn.createStatement()) {
            // NOTE: use absolute path and escape single quotes by replacing ' with '' (very unlikely)
            String abs = snapshotPath.toAbsolutePath().toString().replace("'", "''");
            String vacuumSql = "VACUUM INTO '" + abs + "';";
            s.execute(vacuumSql);
            log.info("[SqliteDNSPersistence] Snapshot exported (VACUUM INTO): " + snapshotPath);
            return snapshotPath.toString();
        } catch (SQLException vacuumEx) {
            // VACUUM INTO might not be supported on older SQLite. Fall back to file copy.
            log.error("[SqliteDNSPersistence] VACUUM INTO failed (fallback to copy): " + vacuumEx.getMessage());
        }

        // Fallback: file copy (best-effort). Copy while DB is open is risky — warn the operator.
        Path original = Path.of(originalPath).toAbsolutePath();
        if (!Files.exists(original)) {
            log.error("[SqliteDNSPersistence] Original DB file not found: " + original);
            return null;
        }
        // Copy file
        try {
            Files.copy(original, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[SqliteDNSPersistence] Snapshot exported (file copy): " + snapshotPath);
            log.error("[SqliteDNSPersistence] NOTE: file copy snapshot may not be crash-consistent if DB is being written to. Prefer VACUUM INTO where possible.");
            return snapshotPath.toString();
        } catch (IOException ioEx) {
            log.error("[SqliteDNSPersistence] Snapshot file copy failed: " + ioEx.getMessage());
            return null;
        }
    }

    /**
     * Export the current snapshot as a Base64 encoded string.
     * This reads the snapshot file (creates one via exportSnapshot() if necessary) and returns its Base64.
     * <p>
     * Use-cases: embedding DB bytes into JSON, sending over RPC, etc.
     *
     * @return Base64 string of the snapshot bytes, or null on failure.
     */
    public synchronized String exportSnapshotAsBase64() {
        String snapPath = exportSnapshot();
        if (snapPath == null) return null;
        Path p = Path.of(snapPath);
        try {
            byte[] bytes = Files.readAllBytes(p);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            log.error("[SqliteDNSPersistence] exportSnapshotAsBase64 failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Deletes all records from the dns_records table.
     * Optionally runs VACUUM to reduce file size.
     * <p>
     * WARNING: This permanently removes all data. Call only when you are certain.
     *
     * @param runVacuum whether to run "VACUUM" after deleting (reclaims space).
     * @return true if truncate succeeded, false otherwise.
     */
    public synchronized boolean truncateDatabase(boolean runVacuum) {
        String deleteSql = "DELETE FROM dns_records;";
        try (Connection conn = connect(); Statement s = conn.createStatement()) {
            conn.setAutoCommit(false);
            int deleted = s.executeUpdate(deleteSql);
            conn.commit();
            log.info("[SqliteDNSPersistence] Truncated dns_records. rowsDeletedApprox=" + deleted);

            if (runVacuum) {
                try {
                    s.execute("VACUUM;");
                    log.info("[SqliteDNSPersistence] VACUUM completed after truncate.");
                } catch (SQLException vacEx) {
                    log.error("[SqliteDNSPersistence] VACUUM after truncate failed: " + vacEx.getMessage());
                    // Not fatal for truncate success
                }
            }
            return true;
        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] truncateDatabase failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Deletes the underlying DB file (useful for tests).
     */
    public synchronized void dropDatabase() {
        try {
            java.io.File f = new java.io.File(dbUrl.replace("jdbc:sqlite:", ""));
            if (f.exists() && f.delete()) {
                log.info("[SqliteDNSPersistence] DNS_FILE deleted.");
            } else {
                log.error("[SqliteDNSPersistence] Failed to delete DNS_FILE.");
            }
        } catch (Exception e) {
            log.error("[SqliteDNSPersistence] Drop DB error: " + e.getMessage());
        }
    }

    /**
     * Executes a custom INSERT SQL statement directly against the dns_records table.
     *
     * <p>Use this method to import or replicate a domain record from another node’s database
     * when you already have the exact INSERT SQL (for example, generated from a remote snapshot).
     *
     * <p><b>Notes:</b>
     * <ul>
     *   <li>This method only allows INSERT statements (case-insensitive check enforced).</li>
     *   <li>It runs in its own transaction for atomicity.</li>
     *   <li>Other statement types (UPDATE/DELETE/SELECT) are rejected for safety.</li>
     *   <li>On success, it logs the number of rows inserted.</li>
     * </ul>
     *
     * <p><b>Example:</b>
     * <pre>
     *   String sql = "INSERT INTO dns_records (name, name_norm, type, ttl, rdata, owner, transaction_hash, timestamp) "
     *              + "VALUES ('example.com', 'example.com', 1, 300, '1.2.3.4', NULL, 'abc123', 1699400000);";
     *   DNSDb.getInstance().executeInsertSQL(sql);
     * </pre>
     *
     * @param insertSQL a valid INSERT statement targeting the dns_records table.
     * @return true if the insert executed successfully, false otherwise.
     */
    public synchronized boolean executeInsertSQL(String insertSQL) {
        if (insertSQL == null || insertSQL.trim().isEmpty()) {
            log.error("[SqliteDNSPersistence] executeInsertSQL failed: SQL string is null or empty.");
            return false;
        }

        // Basic validation: ensure it's an INSERT statement and targets dns_records
        String trimmed = insertSQL.trim().toUpperCase();
        if (!trimmed.startsWith("INSERT")) {
            log.error("[SqliteDNSPersistence] executeInsertSQL rejected: only INSERT statements are allowed.");
            return false;
        }
        if (!trimmed.contains("DNS_RECORDS")) {
            log.error("[SqliteDNSPersistence] executeInsertSQL rejected: statement must target 'dns_records' table.");
            return false;
        }

        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            int rows = stmt.executeUpdate(insertSQL);
            conn.commit();
            log.info("[SqliteDNSPersistence] executeInsertSQL succeeded, rowsInserted=" + rows);
            return true;
        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] executeInsertSQL failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reads every row from the dns_records table in the given SQLite DB file and
     * returns a list of INSERT statements (one per row) that can be executed
     * against another sqlite database to recreate the rows.
     *
     * <p>Notes:
     * <ul>
     *   <li>The input path should point to a valid SQLite file.</li>
     *   <li>This method only reads the dns_records table and ignores any other tables.</li>
     *   <li>Text fields are SQL-escaped (single quotes doubled). NULL values are rendered as NULL (without quotes).</li>
     *   <li>Numeric values are inserted as-is.</li>
     *   <li>If a column name or schema differs in the source DB, this method will still attempt to read the canonical columns:
     *       name, name_norm, type, ttl, rdata, owner, transaction_hash, timestamp.</li>
     * </ul>
     *
     * @param sourceDbFilePath filesystem path to the source SQLite DB file.
     * @return list of INSERT statements (never null; empty list on error or if no rows).
     */
    public synchronized List<String> extractInsertStatementsFromDbFile(String sourceDbFilePath) {
        List<String> inserts = new ArrayList<>();
        if (sourceDbFilePath == null || sourceDbFilePath.trim().isEmpty()) {
            log.error("[SqliteDNSPersistence] extractInsertStatementsFromDbFile failed: source path is null/empty.");
            return inserts;
        }

        java.nio.file.Path src = Path.of(sourceDbFilePath).toAbsolutePath();
        if (!Files.exists(src)) {
            log.error("[SqliteDNSPersistence] extractInsertStatementsFromDbFile failed: source DB not found: " + src);
            return inserts;
        }

        String srcUrl = "jdbc:sqlite:" + src;

        String query = "SELECT name, name_norm, type, ttl, rdata, owner, transaction_hash, timestamp FROM dns_records;";
        try (Connection conn = DriverManager.getConnection(srcUrl);
             PreparedStatement ps = conn.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                // Read columns (nullable)
                String name = rs.getString("name");
                String nameNorm = rs.getString("name_norm");
                // type and ttl may be stored as integer; getObject to detect nulls
                Object typeObj = rs.getObject("type");
                Object ttlObj = rs.getObject("ttl");
                String rdata = rs.getString("rdata");
                String owner = rs.getString("owner");
                String txHash = rs.getString("transaction_hash");
                Object tsObj = rs.getObject("timestamp");

                String sb = "INSERT INTO dns_records (name, name_norm, type, ttl, rdata, owner, transaction_hash, timestamp) VALUES (" +

                        // Helper lambda-like behavior via inline calls
                        // name
                        toSqlValue(name) + ", " +
                        // name_norm
                        toSqlValue(nameNorm) + ", " +
                        // type
                        toSqlValue(typeObj) + ", " +
                        // ttl
                        toSqlValue(ttlObj) + ", " +
                        // rdata
                        toSqlValue(rdata) + ", " +
                        // owner
                        toSqlValue(owner) + ", " +
                        // transaction_hash
                        toSqlValue(txHash) + ", " +
                        // timestamp
                        toSqlValue(tsObj) +
                        ");";
                inserts.add(sb);
            }

            log.info("[SqliteDNSPersistence] extractInsertStatementsFromDbFile completed, rows=" + inserts.size());
            return inserts;
        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] extractInsertStatementsFromDbFile failed: " + e.getMessage());
            return inserts;
        }
    }
    /**
     * Checks whether a DNS record already exists for given (name, type).
     * Enforces uniqueness per (domain, record type).
     *
     * @param name domain name
     * @param type DNS record type
     * @return true if exists, false otherwise
     */
    public synchronized boolean exists(String name, int type) {
        if (name == null) return false;

        String nameNorm = normalize(name);
        String sql = "SELECT 1 FROM dns_records WHERE name_norm = ? AND type = ? LIMIT 1;";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, nameNorm);
            ps.setInt(2, type);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next(); // true if any row exists
            }

        } catch (SQLException e) {
            log.error("[SqliteDNSPersistence] exists(name,type) failed: " + e.getMessage());
            return false;
        }
    }



}
