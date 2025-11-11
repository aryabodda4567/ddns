package org.ddns.db;

import org.ddns.bc.SignatureUtil;
import org.ddns.bc.Transaction;
import org.ddns.bc.TransactionType;
import org.ddns.constants.FileNames;
import org.ddns.dns.DNSModel;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * SQLite-backed persistence for transactions (production-ready).
 * <p>
 * Features:
 * - Singleton thread-safe instance
 * - WAL journaling and sane PRAGMA defaults (busy_timeout increased)
 * - insertTransaction(...) stores payload as JSON using ConversionUtil.toJson(...)
 * - retry-on-busy for write operations with backoff
 * - single-writer executor to avoid concurrent writer contention with SQLite
 * - atomic snapshot export using VACUUM INTO when available, fallback to temp file + atomic move
 * - exportSnapshotAsBase64()
 * - truncateDatabase(...) which runs VACUUM on a fresh connection if requested
 * - executeInsertSQL(...) that only allows INSERT statements against the 'transactions' table
 * - extractInsertStatementsFromDbFile(...) to produce INSERT statements from another DB file
 * - readTransactionByHash(...) returns transaction row (payload JSON left to caller to parse)
 */
public final class TransactionDb {

    // Retry configuration for SQLITE_BUSY
    private static final int SQLITE_BUSY_RETRY = 5;
    private static final long SQLITE_BUSY_BASE_DELAY_MS = 25L;
    private static volatile TransactionDb instance;
    private final String dbUrl;
    // single-writer executor to serialize all DB write operations (prevents SQLITE_BUSY contention)
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TransactionDb-Writer");
        t.setDaemon(true);
        return t;
    });

    private TransactionDb() {
        this.dbUrl = "jdbc:sqlite:" + FileNames.TRANSACTION_DB;
        initialize();
    }

    public static TransactionDb getInstance() {
        if (instance == null) {
            synchronized (TransactionDb.class) {
                if (instance == null) instance = new TransactionDb();
            }
        }
        return instance;
    }

    private static String toSqlValue(Object obj) {
        if (obj == null) return "NULL";
        if (obj instanceof Number) return obj.toString();
        String s = obj.toString();
        String escaped = s.replace("'", "''");
        return "'" + escaped + "'";
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ---------------------------
    // Core API
    // ---------------------------

    /**
     * Create a connection with sane PRAGMA settings. Each call returns a fresh Connection.
     */
    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement s = conn.createStatement()) {
            // WAL helps concurrency (multiple readers + single writer)
            s.execute("PRAGMA journal_mode=WAL;");
            // reasonable trade-off for durability/throughput
            s.execute("PRAGMA synchronous=NORMAL;");
            // increase busy timeout to tolerate contention under stress
            s.execute("PRAGMA busy_timeout=10000;"); // 10s
        } catch (SQLException ignore) {
            // If PRAGMA fails, still return connection — calling code will handle exceptions.
        }
        return conn;
    }

    /**
     * Ensure transactions table exists and create indexes.
     */
    private void initialize() {
        String createTable = """
                    CREATE TABLE IF NOT EXISTS transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        tx_hash TEXT UNIQUE,
                        sender TEXT,
                        type INTEGER NOT NULL,
                        payload TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        signature BLOB
                    );
                """;
        String idxHash = "CREATE INDEX IF NOT EXISTS idx_tx_hash ON transactions(tx_hash);";
        String idxSender = "CREATE INDEX IF NOT EXISTS idx_tx_sender ON transactions(sender);";

        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(createTable);
            stmt.execute(idxHash);
            stmt.execute(idxSender);
            ConsolePrinter.printSuccess("[TransactionDb] initialized.");
        } catch (SQLException e) {
            ConsolePrinter.printFail("[TransactionDb] init failed: " + e.getMessage());
        }
    }

    /**
     *
     * @param transactions Insert a transaction row into the DB.
     *                     This implementation serializes writes via a single-writer executor to avoid
     *                     SQLITE_BUSY under heavy concurrent writers. Caller blocks until write completes.
     * @return true if inserted (rows affected > 0), false otherwise
     */

    public boolean insertTransaction(List<Transaction> transactions) {

        for (Transaction transaction : transactions) {
            boolean success = insertTransaction(
                    transaction.getHash(),
                    transaction.getSenderPublicKey(),
                    transaction.getType(),
                    transaction.getPayload(),
                    transaction.getSignature()
            );

            if (!success) return false;
        }
        return true;

    }

    /**
     * Insert a transaction row into the DB.
     * This implementation serializes writes via a single-writer executor to avoid
     * SQLITE_BUSY under heavy concurrent writers. Caller blocks until write completes.
     *
     * @return true if inserted (rows affected > 0), false otherwise
     */
    public boolean insertTransaction(String txHash,
                                     PublicKey senderPublicKey,
                                     TransactionType type,
                                     List<DNSModel> payload,
                                     byte[] signature) {
        if (txHash == null || type == null || payload == null) {
            ConsolePrinter.printFail("[TransactionDb] insertTransaction failed: txHash/type/payload required.");
            return false;
        }

//        Save DNSRecords
        for (DNSModel dnsModel : payload) {
            boolean success = DNSDb.getInstance().addRecord(dnsModel);
            if (!success) return false;
        }


        // Precompute values (serialization may be somewhat expensive)
        String senderStr = null;
        if (senderPublicKey != null) {
            try {
                senderStr = SignatureUtil.getStringFromKey(senderPublicKey);
            } catch (Exception e) {
                ConsolePrinter.printFail("[TransactionDb] insertTransaction: failed to serialize sender key: " + e.getMessage());
            }
        }

        final String payloadJson;
        try {
            payloadJson = ConversionUtil.toJson(payload);
            if (payloadJson == null) {
                ConsolePrinter.printFail("[TransactionDb] insertTransaction failed: ConversionUtil.toJson returned null.");
                return false;
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[TransactionDb] insertTransaction: payload serialization failed: " + e.getMessage());
            return false;
        }

        final long ts = TimeUtil.getCurrentUnixTime();

        // DB work submitted to single-writer thread to serialize writes.
        String finalSenderStr = senderStr;
        Callable<Boolean> task = () -> {
            String sql = """
                        INSERT OR IGNORE INTO transactions (tx_hash, sender, type, payload, timestamp, signature)
                        VALUES (?, ?, ?, ?, ?, ?);
                    """;

            try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, txHash);
                ps.setString(2, finalSenderStr);
                ps.setInt(3, type.ordinal());
                ps.setString(4, payloadJson);
                ps.setLong(5, ts);
                if (signature != null) ps.setBytes(6, signature);
                else ps.setNull(6, Types.BLOB);

                int attempt = 0;
                while (true) {
                    try {
                        int updated = ps.executeUpdate();
                        if (updated > 0) {
                            ConsolePrinter.printSuccess("[TransactionDb] insertTransaction succeeded txHash=" + txHash + " payloadBytes=" + payloadJson.length());
                            return true;
                        } else {
                            // Insert ignored (likely duplicate unique tx_hash)
                            ConsolePrinter.printFail("[TransactionDb] insertTransaction ignored (duplicate?) txHash=" + txHash);
                            return false;
                        }
                    } catch (SQLException e) {
                        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                        // Detailed logging for diagnosis
                        ConsolePrinter.printFail("[TransactionDb][DEBUG] insert attempt=" + attempt + " txHash=" + txHash + " SQLException: " + e.getMessage());
                        e.printStackTrace();

                        if ((msg.contains("database is locked") || msg.contains("busy")) && attempt < SQLITE_BUSY_RETRY) {
                            attempt++;
                            try {
                                long sleepMs = SQLITE_BUSY_BASE_DELAY_MS * attempt;
                                ConsolePrinter.printFail("[TransactionDb][DEBUG] busy retry sleeping " + sleepMs + "ms (attempt " + attempt + ")");
                                Thread.sleep(sleepMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                ConsolePrinter.printFail("[TransactionDb] insertTransaction interrupted during backoff.");
                                return false;
                            }
                            continue;
                        } else {
                            ConsolePrinter.printFail("[TransactionDb] insertTransaction failed (SQL): " + e.getMessage());
                            return false;
                        }
                    }
                }
            } catch (SQLException e) {
                ConsolePrinter.printFail("[TransactionDb] insertTransaction failed (connect): " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        };

        // Submit to writer and wait for result (blocking) — preserves synchronous API
        Future<Boolean> f = writer.submit(task);
        try {
            return f.get(); // block until write finishes
        } catch (Exception ex) {
            ConsolePrinter.printFail("[TransactionDb] insertTransaction writer execution failed: " + ex.getMessage());
            ex.printStackTrace();
            return false;
        }
    }

    /**
     * Read a transaction row by tx_hash. Payload is returned as JSON string.
     *
     * @param txHash transaction hash to look up
     * @return TransactionRow DTO or null if not found
     */
    public synchronized TransactionRow readTransactionByHash(String txHash) {
        if (txHash == null) return null;
        String path = dbUrl.replace("jdbc:sqlite:", "");
        String url = "jdbc:sqlite:" + path;
        String q = "SELECT tx_hash, sender, type, payload, timestamp, signature FROM transactions WHERE tx_hash = ? LIMIT 1;";

        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, txHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                TransactionRow r = new TransactionRow();
                r.txHash = rs.getString("tx_hash");
                r.sender = rs.getString("sender");
                r.type = rs.getInt("type");
                r.payloadJson = rs.getString("payload");
                r.timestamp = rs.getLong("timestamp");
                r.signature = rs.getBytes("signature");
                return r;
            }
        } catch (SQLException e) {
            ConsolePrinter.printFail("[TransactionDb] readTransactionByHash failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Exports a snapshot of the database to snapshots directory with a timestamped filename.
     * <p>
     * Strategy:
     * - Try to use "VACUUM INTO 'path'" on a fresh connection (atomic and consistent)
     * - If VACUUM INTO fails, fall back to a safe file copy via temp file + atomic move
     *
     * @return absolute path to snapshot file, or null on failure.
     */
    public synchronized String exportSnapshot() {
        String originalPath = dbUrl.replace("jdbc:sqlite:", "");
        Path snapshotDir = Path.of(FileNames.SNAPSHOT_DIR).toAbsolutePath();
        try {
            Files.createDirectories(snapshotDir);
        } catch (IOException e) {
            ConsolePrinter.printFail("[TransactionDb] Failed to create snapshot directory: " + e.getMessage());
            return null;
        }

        // Unique name: millis + nano suffix (very unlikely to collide)
        String timestamp = System.currentTimeMillis() + "-" + System.nanoTime();
        String snapshotName = "transactions_snapshot_" + timestamp + ".db";
        Path snapshotPath = snapshotDir.resolve(snapshotName);

        // First attempt: VACUUM INTO using a fresh connection
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement s = conn.createStatement()) {
            String abs = snapshotPath.toAbsolutePath().toString().replace("'", "''");
            String vacuumSql = "VACUUM INTO '" + abs + "';";
            s.execute(vacuumSql);
            ConsolePrinter.printSuccess("[TransactionDb] Snapshot exported (VACUUM INTO): " + snapshotPath);
            return snapshotPath.toString();
        } catch (SQLException vacuumEx) {
            ConsolePrinter.printFail("[TransactionDb] VACUUM INTO failed (fallback to safe copy): " + vacuumEx.getMessage());
        }

        // Fallback: file copy with temp file and atomic move
        Path original = Path.of(originalPath).toAbsolutePath();
        if (!Files.exists(original)) {
            ConsolePrinter.printFail("[TransactionDb] Original DB file not found: " + original);
            return null;
        }
        Path tmp = snapshotDir.resolve(snapshotName + ".tmp");
        try {
            Files.copy(original, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, snapshotPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicEx) {
                // Some filesystems/platforms (Windows/OneDrive) may not support ATOMIC_MOVE — fallback
                Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
            }
            ConsolePrinter.printSuccess("[TransactionDb] Snapshot exported (file copy): " + snapshotPath);
            ConsolePrinter.printFail("[TransactionDb] NOTE: file copy snapshot may not be crash-consistent if DB is being written to. Prefer VACUUM INTO where supported.");
            return snapshotPath.toString();
        } catch (IOException ioEx) {
            ConsolePrinter.printFail("[TransactionDb] Snapshot file copy failed: " + ioEx.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignore) {
            }
            return null;
        }
    }

    /**
     * Export the current snapshot as a Base64 encoded string.
     *
     * @return Base64 string of snapshot bytes, or null on failure.
     */
    public synchronized String exportSnapshotAsBase64() {
        String snapPath = exportSnapshot();
        if (snapPath == null) return null;
        Path p = Path.of(snapPath);
        try {
            byte[] bytes = Files.readAllBytes(p);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            ConsolePrinter.printFail("[TransactionDb] exportSnapshotAsBase64 failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Deletes all records from the transactions table.
     * Optionally runs VACUUM (on fresh connection) to reduce file size.
     *
     * @param runVacuum whether to run VACUUM after deleting (reclaims space).
     * @return true if truncate succeeded, false otherwise.
     */
    public synchronized boolean truncateDatabase(boolean runVacuum) {
        String deleteSql = "DELETE FROM transactions;";
        try (Connection conn = connect(); Statement s = conn.createStatement()) {
            conn.setAutoCommit(false);
            int deleted = s.executeUpdate(deleteSql);
            conn.commit();
            ConsolePrinter.printSuccess("[TransactionDb] Truncated transactions. rowsDeletedApprox=" + deleted);
        } catch (SQLException e) {
            ConsolePrinter.printFail("[TransactionDb] truncateDatabase failed: " + e.getMessage());
            return false;
        }

        if (runVacuum) {
            // Run VACUUM on a fresh connection (autocommit)
            try (Connection vacConn = DriverManager.getConnection(dbUrl); Statement vs = vacConn.createStatement()) {
                vs.execute("VACUUM;");
                ConsolePrinter.printSuccess("[TransactionDb] VACUUM completed after truncate.");
            } catch (SQLException vacEx) {
                ConsolePrinter.printFail("[TransactionDb] VACUUM after truncate failed: " + vacEx.getMessage());
            }
        }
        return true;
    }

    /**
     * Deletes the underlying DB file (useful for tests).
     */
    public synchronized void dropDatabase() {
        try {
            File f = new File(dbUrl.replace("jdbc:sqlite:", ""));
            if (f.exists() && f.delete()) {
                ConsolePrinter.printSuccess("[TransactionDb] TRANSACTION_DB deleted.");
            } else {
                ConsolePrinter.printFail("[TransactionDb] Failed to delete TRANSACTION_DB.");
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[TransactionDb] Drop DB error: " + e.getMessage());
        }
    }

    // ---------------------------
    // Helpers
    // ---------------------------

    /**
     * Executes a custom INSERT SQL statement directly against the transactions table.
     * Only allows INSERT statements (case-insensitive).
     *
     * @param insertSQL a valid INSERT statement targeting the transactions table.
     * @return true if the insert executed successfully, false otherwise.
     */
    public synchronized boolean executeInsertSQL(String insertSQL) {
        if (insertSQL == null || insertSQL.trim().isEmpty()) {
            ConsolePrinter.printFail("[TransactionDb] executeInsertSQL failed: SQL is null/empty.");
            return false;
        }
        String trimmed = insertSQL.trim().toUpperCase();
        if (!trimmed.startsWith("INSERT")) {
            ConsolePrinter.printFail("[TransactionDb] executeInsertSQL rejected: only INSERT statements are allowed.");
            return false;
        }
        if (!trimmed.contains("TRANSACTIONS")) {
            ConsolePrinter.printFail("[TransactionDb] executeInsertSQL rejected: statement must target 'transactions' table.");
            return false;
        }

        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            int rows = stmt.executeUpdate(insertSQL);
            conn.commit();
            ConsolePrinter.printSuccess("[TransactionDb] executeInsertSQL succeeded, rowsInserted=" + rows);
            return true;
        } catch (SQLException e) {
            ConsolePrinter.printFail("[TransactionDb] executeInsertSQL failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reads every row from the transactions table in the given SQLite DB file and
     * returns a list of INSERT statements (one per row) that can be executed
     * against another sqlite database to recreate the rows.
     *
     * @param sourceDbFilePath filesystem path to the source SQLite DB file.
     * @return list of INSERT statements (never null; empty list on error or if no rows).
     */
    public synchronized List<String> extractInsertStatementsFromDbFile(String sourceDbFilePath) {
        List<String> inserts = new ArrayList<>();
        if (sourceDbFilePath == null || sourceDbFilePath.trim().isEmpty()) {
            ConsolePrinter.printFail("[TransactionDb] extractInsertStatementsFromDbFile failed: source path is null/empty.");
            return inserts;
        }

        Path src = Path.of(sourceDbFilePath).toAbsolutePath();
        if (!Files.exists(src)) {
            ConsolePrinter.printFail("[TransactionDb] extractInsertStatementsFromDbFile failed: source DB not found: " + src);
            return inserts;
        }

        String srcUrl = "jdbc:sqlite:" + src;
        String query = "SELECT tx_hash, sender, type, payload, timestamp, signature FROM transactions;";

        try (Connection conn = DriverManager.getConnection(srcUrl);
             PreparedStatement ps = conn.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String txHash = rs.getString("tx_hash");
                String sender = rs.getString("sender");
                Object typeObj = rs.getObject("type");
                String payload = rs.getString("payload");
                Object tsObj = rs.getObject("timestamp");
                byte[] sig = rs.getBytes("signature");

                StringBuilder sb = new StringBuilder();
                sb.append("INSERT INTO transactions (tx_hash, sender, type, payload, timestamp, signature) VALUES (");
                sb.append(toSqlValue(txHash)).append(", ");
                sb.append(toSqlValue(sender)).append(", ");
                sb.append(toSqlValue(typeObj)).append(", ");
                sb.append(toSqlValue(payload)).append(", ");
                sb.append(toSqlValue(tsObj)).append(", ");
                if (sig == null) {
                    sb.append("NULL");
                } else {
                    sb.append("X'").append(bytesToHex(sig)).append("'");
                }
                sb.append(");");
                inserts.add(sb.toString());
            }

            ConsolePrinter.printSuccess("[TransactionDb] extractInsertStatementsFromDbFile completed, rows=" + inserts.size());
            return inserts;
        } catch (SQLException e) {
            ConsolePrinter.printFail("[TransactionDb] extractInsertStatementsFromDbFile failed: " + e.getMessage());
            return inserts;
        }
    }

    /**
     * Shutdown single-writer executor (for controlled shutdown in tests).
     */
    public synchronized void shutdownWriter() {
        try {
            writer.shutdownNow();
            ConsolePrinter.printSuccess("[TransactionDb] writer shutdown requested.");
        } catch (Exception e) {
            ConsolePrinter.printFail("[TransactionDb] shutdownWriter failed: " + e.getMessage());
        }
    }

    /**
     * Simple DTO for readTransactionByHash results.
     */
    public static class TransactionRow {
        public String txHash;
        public String sender;
        public int type;
        public String payloadJson; // parse using ConversionUtil.fromJson(...) if you want objects
        public long timestamp;
        public byte[] signature;

        @Override
        public String toString() {
            return "TransactionRow{" +
                    "txHash='" + txHash + '\'' +
                    ", sender='" + sender + '\'' +
                    ", type=" + type +
                    ", payloadJson='" + payloadJson + '\'' +
                    ", timestamp=" + timestamp +
                    ", signature=" + Arrays.toString(signature) +
                    '}';
        }
    }
}
