package org.ddns.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.ddns.db.DNSDb;

import java.util.List;

public final class DNSServer {

    private static final Logger log = LoggerFactory.getLogger(DNSServer.class);

    private static volatile DNSServer instance;

    private final DNSPersistence persistence;
    private final DNSCache cache;

    public DNSServer(DNSPersistence persistence) {
        this.persistence = persistence;
        this.cache = new DNSCache();
    }

    /**
     * Starts the DNS server (singleton)
     */
    public static DNSServer start() {
        if (instance == null) {
            synchronized (DNSServer.class) {
                if (instance == null) {
                    instance = new DNSServer(DNSDb.getInstance());
                    log.info("[DNSServer] Started.");
                }
            }
        }
        return instance;
    }

    public static DNSServer get() {
        if (instance == null) {
            throw new IllegalStateException("DNSServer not started. Call DNSServer.start()");
        }
        return instance;
    }

    public static void stop(){
        if (instance == null) {
            throw new IllegalStateException("DNSServer not started. Call DNSServer.start()");
        }
        instance = null;
    }

    // -----------------------------
    // Public API
    // -----------------------------

    public List<DNSModel> lookup(String name, int type) {
        // 1) Cache
        List<DNSModel> cached = cache.get(name, type);
        if (cached != null) {
            return cached;
        }

        // 2) DB
        List<DNSModel> result = persistence.lookup(name, type);

        // 3) Cache
        if (!result.isEmpty()) {
            cache.put(name, type, result);
        }

        return result;
    }

    public List<DNSModel> reverseLookup(String ipOrPtr) {
        return persistence.reverseLookup(ipOrPtr);
    }

    public boolean create(DNSModel record) {
        boolean ok = persistence.addRecord(record);
        if (ok) {
            cache.invalidate(record.getName());
        }
        return ok;
    }

    public boolean update(DNSModel record) {
        boolean ok = persistence.updateRecord(record);
        if (ok) {
            cache.invalidate(record.getName());
        }
        return ok;
    }

    public boolean delete(String name, int type, String rdata) {
        boolean ok = persistence.deleteRecord(name, type, rdata);
        if (ok) {
            cache.invalidate(name);
        }
        return ok;
    }

    public List<DNSModel> listAll() {
        return persistence.listAll();
    }

    /**
     * Used after blockchain re-apply
     */
    public void clearCache() {
        cache.clear();
    }
}
