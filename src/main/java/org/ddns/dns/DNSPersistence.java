package org.ddns.dns;

import java.util.List;

public interface DNSPersistence {

    boolean addRecord(DNSModel record);

    boolean updateRecord(DNSModel record);

    boolean deleteRecord(String name, int type, String rdata);

    List<DNSModel> lookup(String name, int type);

    List<DNSModel> reverseLookup(String ipOrPtr);

    List<DNSModel> listAll();
}
