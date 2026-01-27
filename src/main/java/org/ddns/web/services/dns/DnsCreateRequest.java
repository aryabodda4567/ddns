package org.ddns.web.services.dns;



public class DnsCreateRequest {
    public String origin;
    public String name;
    public String type;
    public String value;
    public long ttl;
    public String ownerBase64;
    public String transactionHash;

}
