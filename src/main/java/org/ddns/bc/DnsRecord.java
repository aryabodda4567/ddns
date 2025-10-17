package org.ddns.bc;

import java.security.PublicKey;
import java.util.Objects;

/**
 * Represents the state of a DNS record in both the blockchain and persistent database.
 * This record can be used for live state caching, serialization, and database persistence.
 */
public class DnsRecord {
    private final String domainName;
    private String type;
    private String recordClass;
    private int ttl;
    private String ipAddress;
    private PublicKey owner;
    private long expiryTimestamp;
    private final long createdAt;
    private long updatedAt;

    public DnsRecord(String domainName, PublicKey owner, String ipAddress, long expiryTimestamp) {
        this.domainName = domainName;
        this.owner = owner;
        this.ipAddress = ipAddress;
        this.expiryTimestamp = expiryTimestamp;

        // Default values for backward compatibility
        this.type = "A";
        this.recordClass = "IN";
        this.ttl = 3600;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public DnsRecord(String domainName, String type, String recordClass, int ttl,
                     String ipAddress, PublicKey owner, long expiryTimestamp,
                     long createdAt, long updatedAt) {
        this.domainName = domainName;
        this.type = type;
        this.recordClass = recordClass;
        this.ttl = ttl;
        this.ipAddress = ipAddress;
        this.owner = owner;
        this.expiryTimestamp = expiryTimestamp;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public DnsRecord(String domainName, long createdAt, String type, String recordClass, int ttl, String ipAddress, PublicKey owner, long expiryTimestamp, long updatedAt) {
        this.domainName = domainName;
        this.createdAt = createdAt;
        this.type = type;
        this.recordClass = recordClass;
        this.ttl = ttl;
        this.ipAddress = ipAddress;
        this.owner = owner;
        this.expiryTimestamp = expiryTimestamp;
        this.updatedAt = updatedAt;
    }



    // --- Getters ---
    public String getDomainName() { return domainName; }
    public String getType() { return type; }
    public String getRecordClass() { return recordClass; }
    public int getTtl() { return ttl; }
    public String getIpAddress() { return ipAddress; }
    public PublicKey getOwner() { return owner; }
    public long getExpiryTimestamp() { return expiryTimestamp; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    // --- Setters ---
    public void setType(String type) { this.type = type; }
    public void setRecordClass(String recordClass) { this.recordClass = recordClass; }
    public void setTtl(int ttl) { this.ttl = ttl; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public void setOwner(PublicKey owner) { this.owner = owner; }
    public void setExpiryTimestamp(long expiryTimestamp) { this.expiryTimestamp = expiryTimestamp; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "DnsRecord{" +
                "domainName='" + domainName + '\'' +
                ", type='" + type + '\'' +
                ", class='" + recordClass + '\'' +
                ", ttl=" + ttl +
                ", ipAddress='" + ipAddress + '\'' +
                ", owner=" + (owner != null ? SignatureUtil.getStringFromKey(owner).substring(0, 15) + "..." : "null") +
                ", expiryTimestamp=" + expiryTimestamp +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }

    // --- Utility Methods ---
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DnsRecord)) return false;
        DnsRecord that = (DnsRecord) o;
        return Objects.equals(domainName, that.domainName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(domainName);
    }
}
