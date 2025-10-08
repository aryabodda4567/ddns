package org.ddns.db;



import org.ddns.bc.DnsRecord;
import org.ddns.bc.SignatureUtil;
import org.ddns.bc.Transaction;

import java.security.PublicKey;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages all interactions with the SQLite database for the dDNS system.
 * This class handles the DNS state table and the transaction logs table.
 */
public class DatabaseManager {

    private final String dbUrl;

    public DatabaseManager(String dbFileName) {
        this.dbUrl = "jdbc:sqlite:" + dbFileName;
        initializeDatabase();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    /**
     * Creates the dns_records and logs tables if they don't already exist.
     */
    private void initializeDatabase() {
        String dnsRecordsTableSql = """
            CREATE TABLE IF NOT EXISTS dns_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                type TEXT NOT NULL,
                class TEXT DEFAULT 'IN',
                ttl INTEGER NOT NULL,
                rdata TEXT NOT NULL UNIQUE,
                owner TEXT NOT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            );""";

        String logsTableSql = """
            CREATE TABLE IF NOT EXISTS logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tx_hash TEXT NOT NULL,
                tx_type TEXT NOT NULL,
                domain_name TEXT,
                payload TEXT,
                sender_key TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            );""";

        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(dnsRecordsTableSql);
            stmt.execute(logsTableSql);
            System.out.println("Database and tables initialized successfully.");
        } catch (SQLException e) {
            System.err.println("Error initializing database: " + e.getMessage());
        }
    }

    // --- CRUD Operations for dns_records ---

    /**
     * Creates a new DNS record in the database.
     */
    public void createDnsRecord(String name, String type, int ttl, String rdata, String owner) {
        String sql = "INSERT INTO dns_records(name, type, ttl, rdata, owner) VALUES(?,?,?,?,?)";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, name);
            pstmt.setString(2, type);
            pstmt.setInt(3, ttl);
            pstmt.setString(4, rdata);
            pstmt.setString(5, owner);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error creating DNS record: " + e.getMessage());
        }
    }

    /**
     * Retrieves a DNS record by its domain name.
     * @param name The domain name to search for.
     * @return A populated DnsRecord object if found, otherwise null.
     */
    public DnsRecord getDnsRecordByName(String name) {
        String sql = "SELECT name, rdata, owner, ttl FROM dns_records WHERE name = ?";

        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, name);
            ResultSet rs = pstmt.executeQuery();

            // Check if a record was found
            if (rs.next()) {
                String domainName = rs.getString("name");
                String ipAddress = rs.getString("rdata");
                String ownerKeyString = rs.getString("owner");
                int ttl = rs.getInt("ttl");

                // Convert the owner string back to a PublicKey object
                PublicKey ownerKey = SignatureUtil.getPublicKeyFromString(ownerKeyString);

                // Calculate an expiry timestamp based on the TTL
                // This is for the in-memory cache object
                long expiryTimestamp = System.currentTimeMillis() + (ttl * 1000L);

                // Create and return the DnsRecord object
                return new DnsRecord(domainName, ownerKey, ipAddress, expiryTimestamp);
            }

        } catch (SQLException e) {
            System.err.println("Error getting DNS record by name: " + e.getMessage());
        } catch (Exception e) {
            // This will catch errors from getPublicKeyFromString
            System.err.println("Error converting public key for domain " + name + ": " + e.getMessage());
        }

        return null; // Return null if no record was found or an error occurred
    }

    /**
     * Performs a reverse lookup to find a domain name by its RDATA (e.g., IP address).
     */
    public String getDomainByRdata(String rdata) {
        String sql = "SELECT name FROM dns_records WHERE rdata = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, rdata);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("name");
            }
        } catch (SQLException e) {
            System.err.println("Error during reverse DNS lookup: " + e.getMessage());
        }
        return null;
    }

    /**
     * Deletes a DNS record by its domain name.
     */
    public void deleteDnsRecord(String name) {
        String sql = "DELETE FROM dns_records WHERE name = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, name);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error deleting DNS record: " + e.getMessage());
        }
    }

    // --- Operations for the logs table ---

    /**
     * Logs a transaction to the 'logs' table for historical record-keeping.
     */
    public void logTransaction(Transaction tx) {
        String sql = "INSERT INTO logs(tx_hash, tx_type, domain_name, payload, sender_key, timestamp) VALUES(?,?,?,?,?,?)";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tx.getHash());
            pstmt.setString(2, tx.getType().toString());
            pstmt.setString(3, tx.getDomainName());
            pstmt.setString(4, new com.google.gson.Gson().toJson(tx.getPayload()));
            pstmt.setString(5, SignatureUtil.getStringFromKey(tx.getSenderPublicKey()));
            pstmt.setLong(6, tx.getTimestamp());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error logging transaction: " + e.getMessage());
        }
    }

    /**
     * Prunes the logs table, deleting records older than the given timestamp.
     * @param cutoffTimestamp The Unix timestamp (milliseconds). Records older than this will be deleted.
     */
    public void pruneLogs(long cutoffTimestamp) {
        String sql = "DELETE FROM logs WHERE timestamp < ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, cutoffTimestamp);
            int rowsAffected = pstmt.executeUpdate();
            System.out.println(rowsAffected + " old log entries pruned.");
        } catch (SQLException e) {
            System.err.println("Error pruning logs: " + e.getMessage());
        }
    }
}