package org.ddns.dns;

import java.security.PublicKey;

public class DNSModel {
    @Override
    public String toString() {
        return "DNSModel{" +
                "name='" + name + '\'' +
                ", type=" + type +
                ", ttl=" + ttl +
                ", rdata='" + rdata + '\'' +
                ", owner=" + owner +
                ", transactionHash='" + transactionHash + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }

    private final String name;
    private final int type;
    private final long ttl;
    private final String rdata;
    private final PublicKey owner;
    private String transactionHash;
    private final long timestamp;

    public DNSModel(String name, int type, long ttl, String rdata, PublicKey owner, String txHash, long timestamp) {
        this.name = name;
        this.type = type;
        this.ttl = ttl;
        this.rdata = rdata;
        this.owner = owner;
        this.transactionHash = txHash;
        this.timestamp = timestamp;
    }

    // getters & setters

    public String getName() { return name; }
    public int getType() { return type; }
    public long getTtl() { return ttl; }
    public String getRdata() { return rdata; }
    public PublicKey getOwner() { return owner; }
    public String getTransactionHash() { return transactionHash; }
    public long getTimestamp() { return timestamp; }

    public void setTransactionHash(String hash){
        this.transactionHash = hash;
    }

    public boolean isExpired() {
        long now = System.currentTimeMillis() / 1000;
        return ttl > 0 && (timestamp + ttl) < now;
    }
}
