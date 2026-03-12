package org.ddns.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.ddns.bc.Block;
import org.ddns.bc.Transaction;
import org.ddns.constants.FileNames;
import org.ddns.util.ConversionUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * SQLite-backed persistence for blockchain blocks.
 * <p>
 * - Stores prunable block headers (hash, previousHash, merkleRoot, timestamp)
 * - Stores full transaction list as JSON (optional)
 * - Supports exporting snapshot, truncate, and reading latest block hash
 * - Thread-safe singleton with retry-on-busy logic
 * <p>
 * Extended with:
 * - extractInsertStatementsFromDbFile(sourcePath) -> List<String> of INSERTs for blocks table
 * - executeInsertSQL(insertSQL) -> execute a validated INSERT into blocks via writer thread
 */
public final class BlockDb {

    private static final Logger log = LoggerFactory.getLogger(BlockDb.class);

    private static final int SQLITE_BUSY_RETRY = 5;
    private static final long SQLITE_BUSY_BASE_DELAY_MS = 25L;
    private static volatile BlockDb instance;
    private final String dbUrl;
    // single-writer executor to serialize all DB write operations (prevents SQLITE_BUSY contention)
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BlockDb-Writer");
        t.setDaemon(true);
        return t;
    });

    private BlockDb() {
        this.dbUrl = "jdbc:sqlite:" + FileNames.BLOCK_DB;
        initialize();
    }

    public static BlockDb getInstance() {
        if (instance == null) {
            synchronized (BlockDb.class) {
                if (instance == null) instance = new BlockDb();
            }
        }
        return instance;
    }

    // ---------------- Initialization ----------------

    private static String toSqlValue(Object obj) {
        if (obj == null) return "NULL";
        if (obj instanceof Number) return obj.toString();
        String s = obj.toString();
        String escaped = s.replace("'", "''");
        return "'" + escaped + "'";
    }

    private void initialize() {
        String createTable = """
                    CREATE TABLE IF NOT EXISTS blocks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        hash TEXT UNIQUE NOT NULL,
                        previous_hash TEXT,
                        merkle_root TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        transactions_json TEXT
                    );
                """;
        String idxHash = "CREATE INDEX IF NOT EXISTS idx_block_hash ON blocks(hash);";
        String idxTs = "CREATE INDEX IF NOT EXISTS idx_block_timestamp ON blocks(timestamp);";

        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(createTable);
            stmt.execute(idxHash);
            stmt.execute(idxTs);
            log.info("Block database initialized");
        } catch (SQLException e) {
            log.error("Block database initialization failed: {}", e.getMessage());
        }
    }

    // ---------------- Core API ----------------

    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL;");
            s.execute("PRAGMA synchronous=NORMAL;");
            s.execute("PRAGMA busy_timeout=10000;");
        } catch (SQLException ignore) {
        }
        return conn;
    }

    /**
     * Inserts a block into the database.
     */
    public boolean insertBlock(Block block) {
        if (block == null) {
            log.error("insertBlock rejected because block is null");
            return false;
        }


        final String txJson;
        try {
            List<Transaction> txs = block.getTransactions();
            txJson = (txs == null) ? null : ConversionUtil.toJson(txs);
        } catch (Exception e) {
            log.error("insertBlock failed to serialize transactions: {}", e.getMessage());
            return false;
        }

        Callable<Boolean> task = () -> {
            String sql = """
                        INSERT OR IGNORE INTO blocks (hash, previous_hash, merkle_root, timestamp, transactions_json)
                        VALUES (?, ?, ?, ?, ?);
                    """;

            try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, block.getHash());
                ps.setString(2, block.getPreviousHash());
                ps.setString(3, block.getMerkleRoot());
                ps.setLong(4, block.getTimestamp());
                if (txJson != null) ps.setString(5, txJson);
                else ps.setNull(5, Types.VARCHAR);

                int attempt = 0;
                while (true) {
                    try {
                        int updated = ps.executeUpdate();
                        if (updated > 0) {
                            log.info("Block persisted with hash {}", block.getHash());
                            return true;
                        } else {
                            log.error("insertBlock ignored; duplicate hash {}", block.getHash());
                            return false;
                        }
                    } catch (SQLException e) {
                        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                        if ((msg.contains("database is locked") || msg.contains("busy")) && attempt < SQLITE_BUSY_RETRY) {
                            attempt++;
                            long sleepMs = SQLITE_BUSY_BASE_DELAY_MS * attempt;
                            Thread.sleep(sleepMs);
                            continue;
                        } else {
                            log.error("insertBlock failed during SQL execution: {}", e.getMessage());
                            return false;
                        }
                    }
                }
            } catch (SQLException e) {
                log.error("insertBlock failed while obtaining connection: {}", e.getMessage());
                return false;
            }
        };

        Future<Boolean> f = writer.submit(task);
        try {
            f.get();
        } catch (Exception e) {
            log.error("insertBlock writer thread failed: {}", e.getMessage());
            return false;
        }
        return true;

    }

    /**
     * Reads a block by its hash.
     */
    public synchronized Block readBlockByHash(String blockHash) {
        if (blockHash == null) return null;
        String sql = "SELECT hash, previous_hash, merkle_root, timestamp, transactions_json FROM blocks WHERE hash = ? LIMIT 1;";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, blockHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                Block block = new Block(rs.getString("previous_hash"), ConversionUtil.jsonToList(rs.getString("transactions_json"), Transaction.class), rs.getLong("timestamp"));

                block.setHash(rs.getString("hash"));

                block.setMerkleRoot(rs.getString("merkle_root"));

                block.setTimestamp(rs.getLong("timestamp"));

                return block;
            }
        } catch (SQLException e) {
            log.error("readBlockByHash failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the latest inserted block hash (by timestamp).
     */
    public synchronized String getLatestBlockHash() {
        String sql = "SELECT hash FROM blocks ORDER BY timestamp DESC LIMIT 1;";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String latestHash = rs.getString("hash");
                    log.info("Latest block hash: {}", latestHash);
                    return latestHash;
                }
                return "0";
            }
        } catch (SQLException e) {
            log.error("getLatestBlockHash failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Deletes all blocks (truncate).
     */
    public synchronized boolean truncateDatabase(boolean vacuum) {
        try (Connection conn = connect(); Statement s = conn.createStatement()) {
            conn.setAutoCommit(false);
            int deleted = s.executeUpdate("DELETE FROM blocks;");
            conn.commit();
            log.info("Blocks truncated; rows deleted={}", deleted);
        } catch (SQLException e) {
            log.error("truncateDatabase failed: {}", e.getMessage());
            return false;
        }

        if (vacuum) {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                s.execute("VACUUM;");
            } catch (SQLException e) {
                log.error("VACUUM after truncate failed: {}", e.getMessage());
            }
        }
        return true;
    }

    /**
     * Exports a snapshot of the block database to /snapshots folder.
     */
    public synchronized String exportSnapshot() {
        Path snapshotDir = Path.of(FileNames.SNAPSHOT_DIR).toAbsolutePath();
        try {
            Files.createDirectories(snapshotDir);
        } catch (IOException e) {
            log.error("Snapshot directory creation failed: {}", e.getMessage());
            return null;
        }

        String timestamp = System.currentTimeMillis() + "-" + System.nanoTime();
        Path snapshotPath = snapshotDir.resolve("block_snapshot_" + timestamp + ".db");

        try (Connection conn = DriverManager.getConnection(dbUrl); Statement s = conn.createStatement()) {
            String abs = snapshotPath.toAbsolutePath().toString().replace("'", "''");
            s.execute("VACUUM INTO '" + abs + "';");
            log.info("Snapshot created at {}", snapshotPath);
            return snapshotPath.toString();
        } catch (SQLException e) {
            log.error("Snapshot VACUUM INTO failed; falling back to file copy: {}", e.getMessage());
        }

        Path original = Path.of(dbUrl.replace("jdbc:sqlite:", ""));
        try {
            Files.copy(original, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Snapshot copied to {}", snapshotPath);
            log.error("File copy snapshot may be inconsistent during writes; prefer VACUUM INTO when supported");
            return snapshotPath.toString();
        } catch (IOException e) {
            log.error("Snapshot file copy failed: {}", e.getMessage());
            return null;
        }
    }

    // ---------------------------
    // New: Extract INSERT statements from another blocks DB file
    // ---------------------------

    /**
     * Shutdown single-writer executor (for controlled shutdown in tests).
     */
    public synchronized void shutdownWriter() {
        try {
            writer.shutdownNow();
        } catch (Exception e) {
            log.error("shutdownWriter failed: {}", e.getMessage());
        }
    }

    /**
     * Reads every row from the blocks table in the given SQLite DB file and
     * returns a list of INSERT statements (one per row) that can be executed
     * against another sqlite database to recreate the rows.
     *
     * @param sourceDbFilePath filesystem path to the source SQLite DB file (blocks DB).
     * @return list of INSERT statements (never null; empty list on error or if no rows).
     */
    public synchronized List<String> extractInsertStatementsFromDbFile(String sourceDbFilePath) {
        List<String> inserts = new ArrayList<>();
        if (sourceDbFilePath == null || sourceDbFilePath.trim().isEmpty()) {
            log.error("extractInsertStatementsFromDbFile failed because source path is blank");
            return inserts;
        }

        Path src = Path.of(sourceDbFilePath).toAbsolutePath();
        if (!Files.exists(src)) {
            log.error("extractInsertStatementsFromDbFile failed because source DB was not found: {}", src);
            return inserts;
        }

        String srcUrl = "jdbc:sqlite:" + src;
        String query = "SELECT hash, previous_hash, merkle_root, timestamp, transactions_json FROM blocks;";

        try (Connection conn = DriverManager.getConnection(srcUrl);
             PreparedStatement ps = conn.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String hash = rs.getString("hash");
                String prev = rs.getString("previous_hash");
                String merkle = rs.getString("merkle_root");
                Object tsObj = rs.getObject("timestamp");
                String txJson = rs.getString("transactions_json");

                String sb = "INSERT INTO blocks (hash, previous_hash, merkle_root, timestamp, transactions_json) VALUES (" +
                        toSqlValue(hash) + ", " +
                        toSqlValue(prev) + ", " +
                        toSqlValue(merkle) + ", " +
                        toSqlValue(tsObj) + ", " +
                        toSqlValue(txJson) +
                        ");";
                inserts.add(sb);
            }

            log.info("Extracted {} INSERT statements from {}", inserts.size(), src);
            return inserts;
        } catch (SQLException e) {
            log.error("extractInsertStatementsFromDbFile failed: {}", e.getMessage());
            return inserts;
        }
    }

    // ---------------- Data model ----------------

    /**
     * Executes a custom INSERT SQL statement directly against the blocks table.
     * Only allows INSERT statements (case-insensitive).
     * <p>
     * The statement is executed on the single-writer executor for safety.
     *
     * @param insertSQL a valid INSERT statement targeting the blocks table.
     */
    public synchronized void executeInsertSQL(String insertSQL) {
        if (insertSQL == null || insertSQL.trim().isEmpty()) {
            log.error("executeInsertSQL rejected because SQL is empty");
            return;
        }
        String trimmed = insertSQL.trim().toUpperCase();
        if (!trimmed.startsWith("INSERT")) {
            log.error("executeInsertSQL rejected because statement is not an INSERT");
            return;
        }
        if (!trimmed.contains("BLOCKS")) {
            log.error("executeInsertSQL rejected because statement must target 'blocks' table");
            return;
        }

        Callable<Boolean> task = () -> {
            try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
                conn.setAutoCommit(false);
                int rows = stmt.executeUpdate(insertSQL);
                conn.commit();
                log.info("executeInsertSQL succeeded; rowsInserted={}", rows);
                return rows > 0;
            } catch (SQLException e) {
                log.error("executeInsertSQL failed: {}", e.getMessage());
                return false;
            }
        };

        Future<Boolean> f = writer.submit(task);
        try {
            f.get();
        } catch (Exception e) {
            log.error("executeInsertSQL writer failure: {}", e.getMessage());
        }
    }

    // ---------------- Helpers ----------------

    /**
     * Reads all blocks from database ordered by timestamp ASC.
     */
    public synchronized List<Block> readAllBlocks() {
        List<Block> blocks = new ArrayList<>();

        String sql = """
                    SELECT hash, previous_hash, merkle_root, timestamp, transactions_json
                    FROM blocks
                    ORDER BY timestamp ASC;
                """;

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {

                String hash = rs.getString("hash");
                String previousHash = rs.getString("previous_hash");
                String merkleRoot = rs.getString("merkle_root");
                long timestamp = rs.getLong("timestamp");
                String txJson = rs.getString("transactions_json");

                List<Transaction> txs;
                if (txJson != null && !txJson.isBlank()) {
                    txs = ConversionUtil.jsonToList(txJson, Transaction.class);
                } else {
                    txs = new ArrayList<>();
                }

                Block block = new Block(previousHash, txs, timestamp);
                block.setHash(hash);
                block.setMerkleRoot(merkleRoot);
                block.setTimestamp(timestamp);

                blocks.add(block);
            }

            log.info("Loaded {} blocks from storage", blocks.size());

        } catch (Exception e) {
            log.error("readAllBlocks failed: {}", e.getMessage());
            e.printStackTrace();
        }

        return blocks;
    }

    public List<Block> readAllBlocksOrdered() {
        String sql = "SELECT hash FROM blocks ORDER BY timestamp ASC;";
        List<Block> out = new ArrayList<>();

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String hash = rs.getString("hash");
                Block b = readBlockByHash(hash);
                if (b != null) out.add(b);
            }
        } catch (Exception e) {
            log.error("readAllBlocksOrdered failed: {}", e.getMessage());
        }
        return out;
    }

    public static class BlockRow {
        public String hash;
        public String previousHash;
        public String merkleRoot;
        public long timestamp;
        public String transactionsJson;

        @Override
        public String toString() {
            String shortTx = (transactionsJson == null)
                    ? "null"
                    : (transactionsJson.length() > 150 ? transactionsJson.substring(0, 150) + "..." : transactionsJson);
            return String.format("""
                            BlockRow{
                              hash='%s',
                              previous='%s',
                              merkleRoot='%s',
                              timestamp=%d,
                              transactions=%s
                            }""",
                    hash, previousHash, merkleRoot, timestamp, shortTx);
        }
    }


}
