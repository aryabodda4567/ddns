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

    private String name;
    private int type;
    private long ttl;
    private String rdata;
    private PublicKey owner;
    private String transactionHash;
    private long timestamp;

    public DNSModel(String name, int type, long ttl, String rdata, PublicKey owner, String txHash) {
        this.name = name;
        this.type = type;
        this.ttl = ttl;
        this.rdata = rdata;
        this.owner = owner;
        this.transactionHash = txHash;
        this.timestamp = System.currentTimeMillis() / 1000;
    }

    // getters & setters

    public String getName() { return name; }
    public int getType() { return type; }
    public long getTtl() { return ttl; }
    public String getRdata() { return rdata; }
    public PublicKey getOwner() { return owner; }
    public String getTransactionHash() { return transactionHash; }
    public long getTimestamp() { return timestamp; }

    public void setTimestamp(long ts) { this.timestamp = ts; }
    public void setTransactionHash(String hash){
        this.transactionHash = hash;
    }

    public boolean isExpired() {
        long now = System.currentTimeMillis() / 1000;
        return ttl > 0 && (timestamp + ttl) < now;
    }
}
