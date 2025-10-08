package org.ddns.bc;

import java.security.PublicKey;

/**
 * A simple data class to represent the current state of a registered domain.
 * This is the object that will be stored in the blockchain's live state map.
 */
public class DnsRecord {
    private final String domainName;
    private PublicKey owner;
    private String ipAddress;
    private long expiryTimestamp;

    public DnsRecord(String domainName, PublicKey owner, String ipAddress, long expiryTimestamp) {
        this.domainName = domainName;
        this.owner = owner;
        this.ipAddress = ipAddress;
        this.expiryTimestamp = expiryTimestamp;
    }

    // --- Getters ---
    public String getDomainName() { return domainName; }
    public PublicKey getOwner() { return owner; }
    public String getIpAddress() { return ipAddress; }
    public long getExpiryTimestamp() { return expiryTimestamp; }

    // --- Setters for state updates ---
    public void setOwner(PublicKey owner) { this.owner = owner; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public void setExpiryTimestamp(long expiryTimestamp) { this.expiryTimestamp = expiryTimestamp; }

    @Override
    public String toString() {
        return "DnsRecord{" +
                "domainName='" + domainName + '\'' +
                ", owner=" + SignatureUtil.getStringFromKey(owner).substring(0, 15) + "..." +
                ", ipAddress='" + ipAddress + '\'' +
                ", expiryTimestamp=" + expiryTimestamp +
                '}';
    }
}
