package org.ddns.dns;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple in-memory implementation of DNSPersistence.
 * Keyed by (lowercased) name -> list of DNSModel
 */
public class InMemoryDNSPersistence implements DNSPersistence {
    private final Map<String, List<DNSModel>> store = new ConcurrentHashMap<>();

    @Override
    public boolean addRecord(DNSModel record) {
        String key = normalize(record.getName());
        store.putIfAbsent(key, new CopyOnWriteArrayList<>());
        List<DNSModel> list = store.get(key);
        // avoid duplicates by equals()
        if (list.contains(record)) return false;
        list.add(record);
        return true;
    }

    @Override
    public boolean updateRecord(DNSModel record) {
        String key = normalize(record.getName());
        store.putIfAbsent(key, new CopyOnWriteArrayList<>());
        List<DNSModel> list = store.get(key);

        // --- START FIXED LOGIC ---
        // Find by name and type ONLY. We want to replace the old rdata.
        for (int i = 0; i < list.size(); i++) {
            DNSModel existing = list.get(i);
            if (existing.getType() == record.getType() && Objects.equals(existing.getName(), record.getName())) {
                // Found the record, update it in place and return.
                list.set(i, record);
                return true;
            }
        }
        // --- END FIXED LOGIC ---

        // If not present, add (upsert behavior)
        list.add(record);
        return true;
    }

    @Override
    public boolean deleteRecord(String name, int type, String rdata) {
        String key = normalize(name);
        List<DNSModel> list = store.get(key);
        if (list == null) return false;
        boolean removed = list.removeIf(m -> m.getType() == type && Objects.equals(m.getRdata(), rdata));
        if (list.isEmpty()) store.remove(key);
        return removed;
    }

    @Override
    public List<DNSModel> lookup(String name, int type) {
        String key = normalize(name);
        List<DNSModel> list = store.getOrDefault(key, Collections.emptyList());
        if (type == -1) return new ArrayList<>(list); // wildcard -1 = any type
        List<DNSModel> out = new ArrayList<>();
        for (DNSModel m : list) if (m.getType() == type) out.add(m);
        return out;
    }

    @Override
    public List<DNSModel> reverseLookup(String ipOrInAddr) {
        // naive: PTRs are stored as PTR records with name like "1.2.3.4.in-addr.arpa."
        String key = normalize(ipOrInAddr);
        List<DNSModel> list = store.getOrDefault(key, Collections.emptyList());
        return new ArrayList<>(list);
    }

    @Override
    public List<DNSModel> listAll() {
        List<DNSModel> out = new ArrayList<>();
        store.values().forEach(out::addAll);
        return out;
    }

    private String normalize(String name) {
        return name == null ? null : name.toLowerCase();
    }
}
