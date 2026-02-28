package org.ddns.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.ddns.db.DNSDb;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

public class DNSResolver implements Resolver {

    private static final Logger log = LoggerFactory.getLogger(DNSResolver.class);

    private final Resolver forwarder;

    public DNSResolver() throws Exception {
        SimpleResolver sr = new SimpleResolver("8.8.8.8");
        sr.setTimeout(Duration.ofSeconds(5));
        sr.setTCP(false);
        this.forwarder = sr;
    }

    public DNSResolver(Resolver forwarder) {
        this.forwarder = forwarder;
    }

    // ---- Delegate config ----

    @Override public void setPort(int port) { forwarder.setPort(port); }
    @Override public void setTCP(boolean tcp) { forwarder.setTCP(tcp); }
    @Override public void setIgnoreTruncation(boolean ignore) { forwarder.setIgnoreTruncation(ignore); }
    @Override public void setEDNS(int level, int payloadSize, int flags, List<EDNSOption> options) {
        forwarder.setEDNS(level, payloadSize, flags, options);
    }
    @Override public void setTSIGKey(TSIG tsig) { forwarder.setTSIGKey(tsig); }
    @Override public void setTimeout(Duration timeout) { forwarder.setTimeout(timeout); }

    // ---- Main logic ----

    @Override
    public Message send(Message query) {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        response.addRecord(query.getQuestion(), Section.QUESTION);

        try {
            Record question = query.getQuestion();
            if (question == null) {
                response.getHeader().setRcode(Rcode.FORMERR);
                return response;
            }

            Name name = question.getName();
            int type = question.getType();
            String domain = name.toString(true);

            List<DNSModel> records = DNSDb.getInstance().lookup(domain, type);

            if (!records.isEmpty()) {
                response.getHeader().setFlag(Flags.AA);

                for (DNSModel m : records) {
                    Record rec = toDnsJavaRecord(name, m);
                    if (rec != null) {
                        response.addRecord(rec, Section.ANSWER);
                    }
                }
                return response;
            }

            // Forward
            return forwarder.send(query);

        } catch (Exception e) {
            log.error("[DNS] Resolver error: " + e.getMessage());
            response.getHeader().setRcode(Rcode.SERVFAIL);
            return response;
        }
    }

    private Record toDnsJavaRecord(Name name, DNSModel m) throws Exception {
        long ttl = Math.max(0, m.getTtl());

        return switch (m.getType()) {
            case Type.A -> new ARecord(name, DClass.IN, ttl, InetAddress.getByName(m.getRdata()));
            case Type.AAAA -> new AAAARecord(name, DClass.IN, ttl, InetAddress.getByName(m.getRdata()));
            case Type.CNAME -> new CNAMERecord(name, DClass.IN, ttl, Name.fromString(m.getRdata() + "."));
            case Type.TXT -> new TXTRecord(name, DClass.IN, ttl, m.getRdata());
            case Type.NS -> new NSRecord(name, DClass.IN, ttl, Name.fromString(m.getRdata() + "."));
            default -> null;
        };
    }

    @Override
    public Object sendAsync(Message message, ResolverListener resolverListener) {
        throw new UnsupportedOperationException("Async DNS not supported");
    }
}
