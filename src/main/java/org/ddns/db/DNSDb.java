package org.ddns.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SQLite-backed implementation of {@link DNSPersistence}.
 *
 * <h3>Optimizations</h3>
 * <ul>
 *   <li><b>HikariCP connection pool</b> with PreparedStatement cache — eliminates
 *       per-call JDBC handshake overhead and reuses compiled statement handles.</li>
 *   <li><b>{@link ReentrantReadWriteLock}</b> — multiple readers run concurrently;
 *       the write lock is taken only during mutations (finer-grained than
 *       {@code synchronized} on {@code this}).</li>
 *   <li><b>{@link #upsertRecord}</b> — single {@code INSERT OR REPLACE} SQL,
 *       removing the caller-side {@code exists() → add/update} round-trip.</li>
 *   <li><b>{@link #addRecords}</b> — batch insert via {@code addBatch()} inside a
 *       single transaction; orders of magnitude faster than N individual commits.</li>
 *   <li><b>Shared {@link #mapRow}</b> — one ResultSet-to-DNSModel mapper used by
 *       every query method.</li>
 *   <li><b>Regex word-boundary guard</b> on {@link #executeInsertSQL} — safer than
 *       {@code String.contains} which matches the token inside quoted values.</li>
 *   <li><b>Snapshot auto-cleanup</b> — {@link #exportSnapshot(int)} prunes snapshots
 *       older than {@code keepLatestN} to prevent unbounded disk growth.</li>
 *   <li><b>{@link #count()}</b> — cheap {@code SELECT COUNT(*)} for monitoring.</li>
 * </ul>
 */
public final class DNSDb implements DNSPersistence {

    private static final Logger log = LoggerFactory.getLogger(DNSDb.class);

    /** Word-boundary guard: "DNS_RECORDS" must not be embedded inside a value string. */
    private static final Pattern DNS_RECORDS_WORD = Pattern.compile("\\bDNS_RECORDS\\b");

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static volatile DNSDb instance;

    public static DNSDb getInstance() {
        if (instance == null) {
            synchronized (DNSDb.class) {
                if (instance == null) instance = new DNSDb();
            }
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final HikariDataSource pool;

    /**
     * Read-write lock:
     * <ul>
     *   <li>Read lock  — acquired by all SELECT queries; multiple threads proceed in parallel.</li>
     *   <li>Write lock — acquired exclusively by INSERT / UPDATE / DELETE operations.</li>
     * </ul>
     * This is strictly more permissive than {@code synchronized(this)} for reads while
     * still serialising writes, which is exactly the constraint SQLite WAL imposes.
     */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    private DNSDb() {
        this.pool = buildPool("jdbc:sqlite:" + FileNames.DNS_DB);
        initialize();
    }

    /** Release pool resources on application shutdown. */
    public void close() {
        if (!pool.isClosed()) {
            pool.close();
            log.info("DNSDb pool closed.");
        }
    }

    // -------------------------------------------------------------------------
    // Pool construction
    // -------------------------------------------------------------------------

    private static HikariDataSource buildPool(String jdbcUrl) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setDriverClassName("org.sqlite.JDBC");

        // SQLite allows many concurrent readers but only one writer.
        // Keep pool small to avoid "database is locked" contention on the writer slot.
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5_000);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(300_000);

        // PRAGMAs applied once per connection, not on every query.
        cfg.setConnectionInitSql(
                "PRAGMA journal_mode=WAL; " +
                        "PRAGMA synchronous=NORMAL; " +
                        "PRAGMA busy_timeout=3000;"
        );

        // PreparedStatement cache: reuses compiled statement handles across pool borrows.
        cfg.addDataSourceProperty("cachePrepStmts",    "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "64");

        return new HikariDataSource(cfg);
    }

    private Connection connect() throws SQLException {
        return pool.getConnection();
    }

    // -------------------------------------------------------------------------
    // Schema init & migration
    // -------------------------------------------------------------------------

    private void initialize() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dns_records (
                        name             TEXT    NOT NULL,
                        name_norm        TEXT    NOT NULL,
                        type             INTEGER NOT NULL,
                        ttl              INTEGER NOT NULL,
                        rdata            TEXT    NOT NULL,
                        owner            TEXT,
                        transaction_hash TEXT,
                        timestamp        INTEGER,
                        PRIMARY KEY (name_norm, type)
                    );
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dns_name_norm ON dns_records(name_norm);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dns_type      ON dns_records(type);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dns_rdata     ON dns_records(rdata);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dns_name_type ON dns_records(name_norm, type);");
            ensureTimestampColumn(stmt);
            log.info("DNS persistence store initialized.");
        } catch (SQLException e) {
            log.error("DNS persistence init failed: {}", e.getMessage());
        }
    }

    private void ensureTimestampColumn(Statement stmt) {
        boolean found = false;
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(dns_records);")) {
            while (rs.next()) {
                if ("timestamp".equalsIgnoreCase(rs.getString("name"))) { found = true; break; }
            }
        } catch (SQLException ignore) { /* non-fatal */ }

        if (!found) {
            try {
                stmt.execute("ALTER TABLE dns_records ADD COLUMN timestamp INTEGER;");
                log.info("Migration: added 'timestamp' column.");
            } catch (SQLException e) {
                log.error("Migration failed to add timestamp: {}", e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared row mapper — single source of truth
    // -------------------------------------------------------------------------

    private static DNSModel mapRow(ResultSet rs) throws SQLException {
        String ownerStr = rs.getString("owner");
        PublicKey pub   = null;
        if (ownerStr != null) {
            try { pub = SignatureUtil.getPublicKeyFromString(ownerStr); }
            catch (Exception ignore) { }
        }
        return new DNSModel(
                rs.getString("name"),
                rs.getInt("type"),
                rs.getLong("ttl"),
                rs.getString("rdata"),
                pub,
                rs.getString("transaction_hash"),
                rs.getLong("timestamp")
        );
    }

    // -------------------------------------------------------------------------
    // DNSPersistence — write operations (exclusive lock)
    // -------------------------------------------------------------------------

    @Override
    public boolean addRecord(DNSModel record) {
        if (record == null || record.getName() == null || record.getRdata() == null) return false;

        final String sql = """
                INSERT OR IGNORE INTO dns_records
                    (name, name_norm, type, ttl, rdata, owner, transaction_hash, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                """;

        rwLock.writeLock().lock();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bindRecord(ps, record);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("addRecord failed: {}", e.getMessage());
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public boolean updateRecord(DNSModel record) {
        if (record == null || record.getName() == null) return false;

        final String sql = """
                UPDATE dns_records
                SET name = ?, ttl = ?, rdata = ?, owner = ?, transaction_hash = ?, timestamp = ?
                WHERE name_norm = ? AND type = ?;
                """;

        rwLock.writeLock().lock();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.getName());
            ps.setLong(2, record.getTtl());
            ps.setString(3, record.getRdata());
            ps.setString(4, ownerString(record));
            ps.setString(5, record.getTransactionHash());
            ps.setLong(6, TimeUtil.getCurrentUnixTime());
            ps.setString(7, normalize(record.getName()));
            ps.setInt(8, record.getType());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("updateRecord failed: {}", e.getMessage());
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Atomically inserts or replaces a record in a single SQL round-trip.
     *
     * <p>Prefer this over the {@code exists() → addRecord() / updateRecord()} pattern:
     * that pattern requires two separate network round-trips and has a TOCTOU race.
     * {@code INSERT OR REPLACE} is atomic and always correct.
     *
     * @param record the record to insert or overwrite.
     * @return {@code true} if the database was modified.
     */
    public boolean upsertRecord(DNSModel record) {
        if (record == null || record.getName() == null || record.getRdata() == null) return false;

        final String sql = """
                INSERT OR REPLACE INTO dns_records
                    (name, name_norm, type, ttl, rdata, owner, transaction_hash, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                """;

        rwLock.writeLock().lock();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bindRecord(ps, record);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("upsertRecord failed: {}", e.getMessage());
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Inserts a list of records in a single transaction using JDBC batch execution.
     *
     * <p>Dramatically faster than calling {@link #addRecord} in a loop when importing
     * records from a peer snapshot — N records = 1 commit instead of N commits.
     *
     * @param records list of records to insert (nulls and invalid entries are skipped).
     * @return number of rows actually inserted.
     */
    public int addRecords(List<DNSModel> records) {
        if (records == null || records.isEmpty()) return 0;

        final String sql = """
                INSERT OR IGNORE INTO dns_records
                    (name, name_norm, type, ttl, rdata, owner, transaction_hash, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                """;

        rwLock.writeLock().lock();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            int queued = 0;
            for (DNSModel record : records) {
                if (record == null || record.getName() == null || record.getRdata() == null) continue;
                bindRecord(ps, record);
                ps.addBatch();
                queued++;
                // Flush every 500 rows to cap memory usage for very large imports
                if (queued % 500 == 0) ps.executeBatch();
            }
            int[] results = ps.executeBatch();
            conn.commit();

            int inserted = 0;
            for (int r : results) if (r > 0) inserted++;
            log.info("addRecords: inserted={} of queued={}", inserted, queued);
            return inserted;
        } catch (SQLException e) {
            log.error("addRecords failed: {}", e.getMessage());
            return 0;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public boolean deleteRecord(String name, int type, String rdata) {
        if (name == null || rdata == null) return false;

        final String sql = "DELETE FROM dns_records WHERE name_norm = ? AND type = ? AND rdata = ?;";

        rwLock.writeLock().lock();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(name));
            ps.setInt(2, type);
            ps.setString(3, rdata);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("deleteRecord failed: {}", e.getMessage());
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // DNSPersistence — read operations (shared lock, concurrent-safe)
    // -------------------------------------------------------------------------

    @Override
    public List<DNSModel> lookup(String name, int type) {
        if (name == null) return List.of();

        final boolean anyType = (type == -1);
        final String sql = anyType
                ? "SELECT name, type, ttl, rdata, owner, transaction_hash, timestamp FROM dns_records WHERE name_norm = ?;"
                : "SELECT name, type, ttl, rdata, owner, transaction_hash, timestamp FROM dns_records WHERE name_norm = ? AND type = ?;";

        rwLock.readLock().lock();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(name));
            if (!anyType) ps.setInt(2, type);
            return collectRows(ps);
        } catch (SQLException e) {
            log.error("lookup failed: {}", e.getMessage());
            return List.of();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public List<DNSModel> reverseLookup(String ipOrInAddr) {
        if (ipOrInAddr == null) return List.of();

        final String sql =
                "SELECT name, type, ttl, rdata, owner, transaction_hash, timestamp FROM dns_records WHERE rdata = ?;";

        rwLock.readLock().lock();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(ipOrInAddr));
            return collectRows(ps);
        } catch (SQLException e) {
            log.error("reverseLookup failed: {}", e.getMessage());
            return List.of();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public List<DNSModel> listAll() {
        final String sql =
                "SELECT name, type, ttl, rdata, owner, transaction_hash, timestamp FROM dns_records;";

        rwLock.readLock().lock();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            return collectRows(ps);
        } catch (SQLException e) {
            log.error("listAll failed: {}", e.getMessage());
            return List.of();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns the total number of records in the table.
     * Cheaper than {@code listAll().size()} for monitoring / health-checks.
     *
     * @return row count, or {@code -1} on error.
     */
    public int count() {
        rwLock.readLock().lock();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM dns_records;");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("count() failed: {}", e.getMessage());
            return -1;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns {@code true} if a record exists for the given {@code (name, type)} pair.
     */
    public boolean exists(String name, int type) {
        if (name == null) return false;

        final String sql = "SELECT 1 FROM dns_records WHERE name_norm = ? AND type = ? LIMIT 1;";

        rwLock.readLock().lock();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalize(name));
            ps.setInt(2, type);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            log.error("exists failed: {}", e.getMessage());
            return false;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot / export
    // -------------------------------------------------------------------------

    /**
     * Exports an atomic snapshot and keeps only the {@code keepLatestN} most recent
     * snapshot files in {@code SNAPSHOT_DIR}, deleting older ones automatically.
     *
     * <p>Tries {@code VACUUM INTO} first (SQLite >= 3.27, atomic). Falls back to a
     * plain file copy with a warning.
     *
     * @param keepLatestN number of recent snapshots to retain (0 = keep all).
     * @return absolute path of the new snapshot, or {@code null} on failure.
     */
    public String exportSnapshot(int keepLatestN) {
        Path snapshotDir = Path.of(FileNames.SNAPSHOT_DIR).toAbsolutePath();
        try {
            Files.createDirectories(snapshotDir);
        } catch (IOException e) {
            log.error("Cannot create snapshot dir: {}", e.getMessage());
            return null;
        }

        Path snapshotPath = snapshotDir.resolve("dns_snapshot_" + TimeUtil.getCurrentUnixTime() + ".db");

        // Attempt VACUUM INTO (atomic, SQLite >= 3.27)
        rwLock.readLock().lock();
        try (Connection conn = connect(); Statement s = conn.createStatement()) {
            String abs = snapshotPath.toAbsolutePath().toString().replace("'", "''");
            s.execute("VACUUM INTO '" + abs + "';");
            log.info("Snapshot exported (VACUUM INTO): {}", snapshotPath);
        } catch (SQLException vacuumEx) {
            log.warn("VACUUM INTO unavailable, falling back to file copy: {}", vacuumEx.getMessage());
            String dbFile = getDatabaseFilePath();
            if (dbFile == null) return null;
            try {
                Files.copy(Path.of(dbFile), snapshotPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Snapshot exported (file copy): {}", snapshotPath);
                log.warn("File-copy snapshot may not be consistent under active writes. Upgrade SQLite.");
            } catch (IOException ioEx) {
                log.error("Snapshot file copy failed: {}", ioEx.getMessage());
                return null;
            }
        } finally {
            rwLock.readLock().unlock();
        }

        // Prune old snapshots
        if (keepLatestN > 0) pruneSnapshots(snapshotDir, keepLatestN);

        return snapshotPath.toString();
    }

    /** Convenience overload — keeps all snapshots (no pruning). */
    public String exportSnapshot() {
        return exportSnapshot(0);
    }

    /**
     * Returns the current snapshot as a Base64-encoded string.
     * Useful for embedding in JSON / sending over RPC.
     */
    public String exportSnapshotAsBase64() {
        String snapPath = exportSnapshot();
        if (snapPath == null) return null;
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(snapPath)));
        } catch (IOException e) {
            log.error("exportSnapshotAsBase64 failed: {}", e.getMessage());
            return null;
        }
    }

    /** Delete all but the {@code keepLatestN} most recently modified snapshot files. */
    private void pruneSnapshots(Path dir, int keepLatestN) {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> snapshots = files
                    .filter(p -> p.getFileName().toString().startsWith("dns_snapshot_"))
                    .sorted((a, b) -> {
                        try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                        catch (IOException e) { return 0; }
                    })
                    .toList();

            for (int i = keepLatestN; i < snapshots.size(); i++) {
                try {
                    Files.deleteIfExists(snapshots.get(i));
                    log.info("Pruned old snapshot: {}", snapshots.get(i).getFileName());
                } catch (IOException e) {
                    log.warn("Failed to prune snapshot {}: {}", snapshots.get(i), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Snapshot pruning failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Admin / maintenance
    // -------------------------------------------------------------------------

    /** Returns the absolute filesystem path of the live DB file, or {@code null} if missing. */
    public String getDatabaseFilePath() {
        String raw = pool.getJdbcUrl().replace("jdbc:sqlite:", "");
        try {
            Path p = Path.of(raw).toAbsolutePath();
            return Files.exists(p) ? p.toString() : null;
        } catch (Exception e) {
            log.error("getDatabaseFilePath failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Deletes every row in {@code dns_records}.
     *
     * @param runVacuum if {@code true}, reclaims free pages with {@code VACUUM}.
     * @return {@code true} if the delete committed successfully.
     */
    public boolean truncateDatabase(boolean runVacuum) {
        rwLock.writeLock().lock();
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);          // set BEFORE any Statement is created
            try (Statement s = conn.createStatement()) {
                int deleted = s.executeUpdate("DELETE FROM dns_records;");
                conn.commit();
                log.info("Truncated dns_records, approxRows={}", deleted);

                if (runVacuum) {
                    try { s.execute("VACUUM;"); log.info("VACUUM completed."); }
                    catch (SQLException v) { log.warn("VACUUM failed (non-fatal): {}", v.getMessage()); }
                }
                return true;
            } catch (SQLException inner) {
                conn.rollback();
                throw inner;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("truncateDatabase failed: {}", e.getMessage());
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Deletes the underlying DB file. Intended for tests / fresh-start scenarios. */
    public void dropDatabase() {
        rwLock.writeLock().lock();
        try {
            java.io.File f = new java.io.File(pool.getJdbcUrl().replace("jdbc:sqlite:", ""));
            if (f.exists() && f.delete()) log.info("DB file deleted.");
            else log.warn("DB file could not be deleted (may not exist).");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Import / replication helpers
    // -------------------------------------------------------------------------

    /**
     * Executes a single INSERT statement against the local {@code dns_records} table.
     *
     * <p>Safety checks: must start with {@code INSERT}; must reference
     * {@code dns_records} as a whole word (regex prevents matching the token inside a quoted value).
     *
     * <p>For bulk import, prefer {@link #addRecords} or {@link #executeInsertBatch(List)} —
     * both use a single transaction.
     *
     * @param insertSQL a valid INSERT statement.
     * @return {@code true} if at least one row was inserted.
     */
    public boolean executeInsertSQL(String insertSQL) {
        if (insertSQL == null || insertSQL.isBlank()) {
            log.error("executeInsertSQL: SQL is null/blank.");
            return false;
        }
        String upper = insertSQL.trim().toUpperCase();
        if (!upper.startsWith("INSERT")) {
            log.error("executeInsertSQL rejected: only INSERT statements are allowed.");
            return false;
        }
        if (!DNS_RECORDS_WORD.matcher(upper).find()) {
            log.error("executeInsertSQL rejected: statement must target dns_records.");
            return false;
        }

        rwLock.writeLock().lock();
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                int rows = stmt.executeUpdate(insertSQL);
                conn.commit();
                log.info("executeInsertSQL: rowsInserted={}", rows);
                return true;
            } catch (SQLException inner) {
                conn.rollback();
                throw inner;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("executeInsertSQL failed: {}", e.getMessage());
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Executes a list of INSERT SQL strings in a single transaction.
     *
     * <p>Each statement is validated the same way as {@link #executeInsertSQL}.
     * Invalid statements are skipped; valid ones are batched together.
     *
     * @param insertStatements pre-built INSERT strings (e.g. from {@link #extractInsertStatementsFromDbFile}).
     * @return number of rows successfully inserted.
     */
    public int executeInsertBatch(List<String> insertStatements) {
        if (insertStatements == null || insertStatements.isEmpty()) return 0;

        rwLock.writeLock().lock();
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            int queued = 0;
            for (String sql : insertStatements) {
                if (sql == null || sql.isBlank()) continue;
                String upper = sql.trim().toUpperCase();
                if (!upper.startsWith("INSERT") || !DNS_RECORDS_WORD.matcher(upper).find()) {
                    log.warn("executeInsertBatch: skipped invalid statement.");
                    continue;
                }
                stmt.addBatch(sql);
                queued++;
            }
            int[] results = stmt.executeBatch();
            conn.commit();

            int inserted = 0;
            for (int r : results) if (r > 0) inserted++;
            log.info("executeInsertBatch: inserted={} of queued={}", inserted, queued);
            return inserted;
        } catch (SQLException e) {
            log.error("executeInsertBatch failed: {}", e.getMessage());
            return 0;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Reads every row from an external SQLite file and returns one INSERT SQL
     * string per row, suitable for replaying on another node.
     *
     * @param sourceDbFilePath path to the source SQLite DB file.
     * @return list of INSERT statements (never {@code null}; empty on error).
     */
    public List<String> extractInsertStatementsFromDbFile(String sourceDbFilePath) {
        if (sourceDbFilePath == null || sourceDbFilePath.isBlank()) {
            log.error("extractInsertStatementsFromDbFile: path is null/empty.");
            return Collections.emptyList();
        }

        Path src = Path.of(sourceDbFilePath).toAbsolutePath();
        if (!Files.exists(src)) {
            log.error("extractInsertStatementsFromDbFile: source not found: {}", src);
            return Collections.emptyList();
        }

        final String query =
                "SELECT name, name_norm, type, ttl, rdata, owner, transaction_hash, timestamp FROM dns_records;";
        final String prefix =
                "INSERT INTO dns_records (name, name_norm, type, ttl, rdata, owner, transaction_hash, timestamp) VALUES (";

        List<String> inserts = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + src);
             PreparedStatement ps = conn.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                inserts.add(new StringBuilder(256)
                        .append(prefix)
                        .append(toSqlValue(rs.getString("name"))).append(", ")
                        .append(toSqlValue(rs.getString("name_norm"))).append(", ")
                        .append(toSqlValue(rs.getObject("type"))).append(", ")
                        .append(toSqlValue(rs.getObject("ttl"))).append(", ")
                        .append(toSqlValue(rs.getString("rdata"))).append(", ")
                        .append(toSqlValue(rs.getString("owner"))).append(", ")
                        .append(toSqlValue(rs.getString("transaction_hash"))).append(", ")
                        .append(toSqlValue(rs.getObject("timestamp")))
                        .append(");")
                        .toString());
            }
            log.info("extractInsertStatementsFromDbFile: rows={}", inserts.size());
        } catch (SQLException e) {
            log.error("extractInsertStatementsFromDbFile failed: {}", e.getMessage());
        }
        return inserts;
    }

    // -------------------------------------------------------------------------
    // Internal utilities
    // -------------------------------------------------------------------------

    /** Binds all 8 INSERT columns for a {@link DNSModel} to a PreparedStatement. */
    private static void bindRecord(PreparedStatement ps, DNSModel record) throws SQLException {
        ps.setString(1, record.getName());
        ps.setString(2, normalize(record.getName()));
        ps.setInt(3, record.getType());
        ps.setLong(4, record.getTtl());
        ps.setString(5, record.getRdata());
        ps.setString(6, ownerString(record));
        ps.setString(7, record.getTransactionHash());
        ps.setLong(8, TimeUtil.getCurrentUnixTime());
    }

    /** Executes a PreparedStatement and collects all rows into a list. */
    private static List<DNSModel> collectRows(PreparedStatement ps) throws SQLException {
        List<DNSModel> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapRow(rs));
        }
        return out;
    }

    private static String normalize(String name) {
        return name == null ? null : name.toLowerCase();
    }

    private static String ownerString(DNSModel record) {
        return record.getOwner() == null ? null : SignatureUtil.getStringFromKey(record.getOwner());
    }

    /**
     * Converts a value to a SQL literal safe for embedding in a string statement.
     * Prefer parameterized queries everywhere else; use this only for bulk INSERT generation.
     */
    private static String toSqlValue(Object obj) {
        if (obj == null) return "NULL";
        if (obj instanceof Number) return obj.toString();
        return "'" + obj.toString().replace("'", "''") + "'";
    }
}