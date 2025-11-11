package org.ddns.dns;

import java.util.List;

/**
 * DNSPersistence - pluggable persistence interface.
 */
public interface DNSPersistence {
    /**
     * Add a record. Returns true if added (false if exists or failed).
     */
    boolean addRecord(DNSModel record);

    /**
     * Update a record. Upsert semantics if you want, but here it's explicit update.
     */
    boolean updateRecord(DNSModel record);

    /**
     * Delete a record by name and type.
     */
    boolean deleteRecord(String name, int type, String rdata);

    /**
     * Lookup records for a name and type.
     */
    List<DNSModel> lookup(String name, int type);

    /**
     * Reverse lookup -- find PTR(s) for given IP (or textual address)
     */
    List<DNSModel> reverseLookup(String ipOrInAddr);

    /**
     * Optional: list all records (for admin/debug)
     */
    List<DNSModel> listAll();
}
