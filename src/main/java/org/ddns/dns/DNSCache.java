package org.ddns.dns;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DNSCache {

    private static final class CacheKey {
        final String name;
        final int type;

        CacheKey(String n, int t) {
            this.name = n.toLowerCase();
            this.type = t;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof CacheKey k)) return false;
            return name.equals(k.name) && type == k.type;
        }

        @Override public int hashCode() {
            return Objects.hash(name, type);
        }
    }

    private final Map<CacheKey, List<DNSModel>> cache = new ConcurrentHashMap<>();

    public void put(String name, int type, List<DNSModel> records) {
        cache.put(new CacheKey(name, type), records);
    }

    public List<DNSModel> get(String name, int type) {
        CacheKey k = new CacheKey(name, type);
        List<DNSModel> list = cache.get(k);
        if (list == null) return null;

        // purge expired
        List<DNSModel> valid = new ArrayList<>();
        for (DNSModel r : list) {
            if (!r.isExpired()) valid.add(r);
        }
        if (valid.isEmpty()) {
            cache.remove(k);
            return null;
        }
        return valid;
    }

    public void invalidate(String name) {
        String n = name.toLowerCase();
        cache.keySet().removeIf(k -> k.name.equals(n));
    }

    public void clear() {
        cache.clear();
    }
}
