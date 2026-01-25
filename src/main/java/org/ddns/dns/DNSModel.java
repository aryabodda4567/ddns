package org.ddns.dns;

import org.ddns.util.TimeUtil;

import java.security.PublicKey;
import java.util.Objects;

/**
 * DNSModel represents a DNS resource record in our persistence layer.
 */
public class DNSModel {
    private final String name;      // FQDN, e.g. "www.example.com."
    private final int type;         // DNS type from org.xbill.DNS.Type (A, AAAA, PTR, TXT, etc.)
    private final long ttl;         // TTL in seconds
    private final String rdata;     // textual representation of RDATA (e.g. "1.2.3.4" for A)
    private final PublicKey owner;  // owner public key
    private final String transactionHash; // optional transaction hash (nullable)
    private long timestamp; // Latest update time

    public DNSModel(String name, int type, long ttl, String rdata, PublicKey owner, String transactionHash) {
        this.name = name;
        this.type = type;
        this.ttl = ttl;
        this.rdata = rdata;
        this.owner = owner;
        this.transactionHash = transactionHash;
        timestamp = TimeUtil.getCurrentUnixTime();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        DNSModel dnsModel = (DNSModel) o;
        return type == dnsModel.type && ttl == dnsModel.ttl && timestamp == dnsModel.timestamp && Objects.equals(name, dnsModel.name) && Objects.equals(rdata, dnsModel.rdata) && Objects.equals(owner, dnsModel.owner) && Objects.equals(transactionHash, dnsModel.transactionHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, ttl, rdata, owner, transactionHash, timestamp);
    }

    public String getName() {
        return name;
    }

    public int getType() {
        return type;
    }

    public long getTtl() {
        return ttl;
    }

    public String getRdata() {
        return rdata;
    }

    public PublicKey getOwner() {
        return owner;
    }

    public String getTransactionHash() {
        return transactionHash;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

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

}

