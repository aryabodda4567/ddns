package org.ddns.dns;

import org.ddns.db.DNSDb;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.net.InetAddress;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * DNSHandler - processes incoming DNS {@link Message} objects and produces replies.
 * <p>
 * Responsibilities:
 * - Serve normal DNS lookups (A, AAAA, PTR, TXT, etc.) by reading from {@link DNSPersistence}.
 * - Process management commands encoded as a TXT-query subdomain starting with "_cmd.".
 * <p>
 * Production-hardened and uses ConsolePrinter for console output.
 */
public class DNSHandler {

    private final DNSPersistence persistence;
    private final Name serverOrigin; // canonical origin (e.g., "example.com.")

    public DNSHandler(DNSPersistence persistence, String origin) throws TextParseException {
        if (persistence == null) throw new IllegalArgumentException("persistence required");
        if (origin == null || origin.isBlank()) throw new IllegalArgumentException("origin required");
        this.persistence = persistence;
        this.serverOrigin = new Name(canonicalize(origin));
        ConsolePrinter.printInfo("DNSHandler created for origin " + this.serverOrigin);
    }

    /**
     * Handle an incoming DNS query Message and return a reply Message.
     * This method never throws; it converts internal failures into SERVFAIL or TXT error messages.
     */
    public Message handle(Message query) {
        try {
            Record question = query.getQuestion();
            if (question == null) {
                return errorMessage(query, Rcode.FORMERR);
            }

            Name qname = question.getName();
            int qtype = question.getType();

            // Management command if name begins with _cmd.
            if (isManagementQuery(qname)) {
                return handleManagement(query, qname);
            }

            // Normal DNS lookup
            return handleLookup(query, qname, qtype);
        } catch (Exception e) {
            ConsolePrinter.printFail("Failed to handle query: " + e);
            return errorMessage(query, Rcode.SERVFAIL);
        }
    }

    // --- Management handling ---

    /**
     * Returns true if qname's first label equals "_cmd" (case-insensitive).
     */
    private boolean isManagementQuery(Name qname) {
        try {
            if (qname == null) return false;
            String first = qname.getLabelString(0);
            return first != null && first.equalsIgnoreCase("_cmd");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Handle management (command) queries formatted as:
     * _cmd.<b64chunk1>.<b64chunk2>....<origin>.
     */
    private Message handleManagement(Message query, Name qname) {
        try {
            // ensure command is for our origin
            if (!qname.subdomain(this.serverOrigin)) {
                ConsolePrinter.printWarning("Management command rejected: " + qname + " is not a subdomain of " + serverOrigin);
                return txtResponse(query, "ERR: command must be a subdomain of " + this.serverOrigin);
            }

            // Build array of labels (absolute representation with trailing dot removed)
            String abs = qname.toString(true); // includes trailing dot
            String[] labels = abs.split("\\.");
            int labelCount = labels.length;
            if (labelCount == 0) return txtResponse(query, "ERR: invalid query name");

            int originLabels = this.serverOrigin.labels();
            int payloadLabelCount = qname.labels() - originLabels; // number of labels before origin

            if (payloadLabelCount < 2) {
                // need at least "_cmd" + 1 payload label
                return txtResponse(query, "ERR: invalid command format. Expected _cmd.<payload>.<origin>");
            }

            // assemble payload from labels[1] .. labels[payloadLabelCount-1]
            StringBuilder payloadB64 = new StringBuilder();
            for (int i = 1; i < payloadLabelCount; i++) {
                payloadB64.append(labels[i]);
            }

            // decode Base64 URL safe
            byte[] decoded;
            try {
                decoded = Base64.getUrlDecoder().decode(payloadB64.toString());
            } catch (IllegalArgumentException iae) {
                ConsolePrinter.printWarning("Base64 decode failed for payload: " + iae.getMessage());
                return txtResponse(query, "ERR: invalid base64 payload");
            }

            String json = new String(decoded);
            ConsolePrinter.printInfo("Decoded management JSON: " + json);

            Map<String, Object> cmd = ConversionUtil.jsonToMapObject(json);
            if (cmd == null) {
                return txtResponse(query, "ERR: invalid JSON command");
            }

            // Safe action extraction
            Object actionObj = cmd.get("action");
            if (actionObj == null) {
                return txtResponse(query, "ERR: missing action");
            }
            String action = actionObj.toString().trim().toUpperCase();

            switch (action) {
                case "CREATE":
                    return handleCreate(query, cmd);
                case "UPDATE":
                    return handleUpdate(query, cmd);
                case "DELETE":
                    return handleDelete(query, cmd);
                case "LOOKUP":
                    return handleLookupCommand(query, cmd);
                case "REVERSE_LOOKUP":
                    return handleReverseLookupCommand(query, cmd);
                default:
                    return txtResponse(query, "ERR: unknown action: " + action);
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("Management parse error: " + e);
            return txtResponse(query, "ERR: " + e.getMessage());
        }
    }

    // --- Command implementations (CREATE/UPDATE/DELETE/LOOKUP/REVERSE_LOOKUP) ---

    private Message handleCreate(Message query, Map<String, Object> cmd) {
        try {
            String name = getString(cmd, "name");
            String typeStr = getString(cmd, "type");
            String value = getString(cmd, "value");
            long ttl = getLong(cmd, "ttl", 300L);
            String ownerB64 = getString(cmd, "ownerBase64");
            String txHash = getString(cmd, "transactionHash");

            PublicKey owner = null;
            if (ownerB64 != null && !ownerB64.isBlank()) {
                owner = toPublicKey(ownerB64);
            }

            int type = Type.value(typeStr);

            DNSModel rec = new DNSModel(canonicalize(name), type, ttl, value, owner, txHash);

            // Optional: Verify ownership/signature before allowing create (hook).
            if (owner != null) {
                if (!verifyOwnerSignature(cmd, owner)) {
                    ConsolePrinter.printWarning("Create command rejected due to owner/signature verification failure for " + name);
                    return txtResponse(query, "ERR: signature verification failed");
                }
            }

//            boolean added = persistence.addRecord(rec);
            boolean added = DNSDb.getInstance().addRecord(rec);
            return txtResponse(query, added ? "OK: created" : "ERR: already exists");
        } catch (IllegalArgumentException iae) {
            ConsolePrinter.printWarning("Create failed validation: " + iae.getMessage());
            return txtResponse(query, "ERR: " + iae.getMessage());
        } catch (Exception ex) {
            ConsolePrinter.printFail("Create failed: " + ex);
            return txtResponse(query, "ERR: " + ex.getMessage());
        }
    }

    private Message handleUpdate(Message query, Map<String, Object> cmd) {
        try {
            String name = getString(cmd, "name");
            String typeStr = getString(cmd, "type");
            String value = getString(cmd, "value");
            long ttl = getLong(cmd, "ttl", 300L);
            String ownerB64 = getString(cmd, "ownerBase64");
            String txHash = getString(cmd, "transactionHash");

            PublicKey owner = null;
            if (ownerB64 != null && !ownerB64.isBlank()) owner = toPublicKey(ownerB64);
            int type = Type.value(typeStr);

            DNSModel rec = new DNSModel(canonicalize(name), type, ttl, value, owner, txHash);

            // Optional verification hook
            if (owner != null && !verifyOwnerSignature(cmd, owner)) {
                ConsolePrinter.printWarning("Update rejected: signature verification failed for " + name);
                return txtResponse(query, "ERR: signature verification failed");
            }

//            boolean updated = persistence.updateRecord(rec);
            boolean updated = DNSDb.getInstance().updateRecord(rec);
            return txtResponse(query, updated ? "OK: updated" : "ERR: update failed");
        } catch (IllegalArgumentException iae) {
            ConsolePrinter.printWarning("Update failed validation: " + iae.getMessage());
            return txtResponse(query, "ERR: " + iae.getMessage());
        } catch (Exception ex) {
            ConsolePrinter.printFail("Update failed: " + ex);
            return txtResponse(query, "ERR: " + ex.getMessage());
        }
    }

    private Message handleDelete(Message query, Map<String, Object> cmd) {
        try {
            String name = getString(cmd, "name");
            String typeStr = getString(cmd, "type");
            String value = getString(cmd, "value");

            int type = Type.value(typeStr);

//            boolean deleted = persistence.deleteRecord(canonicalize(name), type, value);
            boolean deleted = DNSDb.getInstance().deleteRecord(canonicalize(name), type, value);
            return txtResponse(query, deleted ? "OK: deleted" : "ERR: not found");
        } catch (IllegalArgumentException iae) {
            ConsolePrinter.printWarning("Delete failed validation: " + iae.getMessage());
            return txtResponse(query, "ERR: " + iae.getMessage());
        } catch (Exception ex) {
            ConsolePrinter.printFail("Delete failed: " + ex);
            return txtResponse(query, "ERR: " + ex.getMessage());
        }
    }

    private Message handleLookupCommand(Message query, Map<String, Object> cmd) {
        try {
            String name = getString(cmd, "name");
            String typeStr = (String) cmd.getOrDefault("type", "ANY");
            int type = Type.value(typeStr);
//            List<DNSModel> result = persistence.lookup(canonicalize(name), type == Type.ANY ? -1 : type);
            List<DNSModel> result = DNSDb.getInstance().lookup(canonicalize(name), type == Type.ANY ? -1 : type);

            StringBuilder sb = new StringBuilder();
            if (result.isEmpty()) sb.append("EMPTY");
            else {
                for (DNSModel m : result) {
                    sb.append(m.getName()).append(" ").append(Type.string(m.getType()))
                            .append(" ").append(m.getRdata()).append("; ");
                }
            }
            return txtResponse(query, sb.toString());
        } catch (Exception ex) {
            ConsolePrinter.printFail("Lookup command failed: " + ex);
            return txtResponse(query, "ERR: " + ex.getMessage());
        }
    }

    private Message handleReverseLookupCommand(Message query, Map<String, Object> cmd) {
        try {
            String ip = getString(cmd, "ip");
//            List<DNSModel> result = persistence.reverseLookup(ip);
            List<DNSModel> result = DNSDb.getInstance().reverseLookup(ip);

            StringBuilder sb = new StringBuilder();
            if (result.isEmpty()) sb.append("EMPTY");
            else {
                for (DNSModel m : result) {
                    sb.append(m.getName()).append(" PTR ").append(m.getRdata()).append("; ");
                }
            }
            return txtResponse(query, sb.toString());
        } catch (Exception ex) {
            ConsolePrinter.printFail("Reverse lookup cmd failed: " + ex);
            return txtResponse(query, "ERR: " + ex.getMessage());
        }
    }

    // --- Normal DNS lookup handling ---

    private Message handleLookup(Message query, Name qname, int qtype) {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR); // reply
        response.addRecord(query.getQuestion(), Section.QUESTION);

//        List<DNSModel> matches = persistence.lookup(qname.toString(), qtype == Type.ANY ? -1 : qtype);
        List<DNSModel> matches = DNSDb.getInstance().lookup(qname.toString(), qtype == Type.ANY ? -1 : qtype);
        for (DNSModel m : matches) {
            try {
                Record rr = toRecord(m);
                response.addRecord(rr, Section.ANSWER);
            } catch (Exception e) {
                ConsolePrinter.printWarning("Failed creating RR for " + m + " : " + e);
            }
        }

        response.getHeader().setRcode(matches.isEmpty() ? Rcode.NXDOMAIN : Rcode.NOERROR);
        return response;
    }

    // --- Helper methods for message creation / parsing ---

    /**
     * Builds a TXT reply message for the original question (if present) or for the server origin otherwise.
     */
    private Message txtResponse(Message query, String txt) {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        Record q = query.getQuestion();
        if (q != null) response.addRecord(q, Section.QUESTION);

        Name name = q != null ? q.getName() : serverOrigin;
        TXTRecord txtRec = new TXTRecord(name, DClass.IN, 0, txt);
        response.addRecord(txtRec, Section.ANSWER);
        response.getHeader().setRcode(Rcode.NOERROR);
        return response;
    }

    private Message errorMessage(Message query, int rcode) {
        Message resp = new Message(query.getHeader().getID());
        resp.getHeader().setFlag(Flags.QR);
        resp.getHeader().setRcode(rcode);
        return resp;
    }

    // --- Utility helpers ---

    private PublicKey toPublicKey(String ownerBase64) throws Exception {
        byte[] bytes = Base64.getUrlDecoder().decode(ownerBase64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(bytes);
        // try RSA then EC
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        } catch (Exception ignored) {
        }
        KeyFactory kf = KeyFactory.getInstance("EC");
        return kf.generatePublic(spec);
    }

    /**
     * Convert DNSModel into a dnsjava Record.
     * Uses InetAddress.getByName(...) for A/AAAA to accept textual IPs reliably.
     */
    private Record toRecord(DNSModel m) throws Exception {
        Name name = Name.fromString(m.getName());
        int type = m.getType();
        long ttl = m.getTtl();
        String rdata = m.getRdata();

        switch (type) {
            case Type.A: {
                InetAddress addr = InetAddress.getByName(rdata); // handles dotted IPv4
                return new ARecord(name, DClass.IN, ttl, addr);
            }
            case Type.AAAA: {
                InetAddress addr = InetAddress.getByName(rdata); // handles IPv6 text
                return new AAAARecord(name, DClass.IN, ttl, addr);
            }
            case Type.TXT:
                return new TXTRecord(name, DClass.IN, ttl, rdata);
            case Type.PTR:
                // ensure rdata is a valid domain name (canonicalized)
                Name target = new Name(canonicalize(rdata));
                return new PTRRecord(name, DClass.IN, ttl, target);
            default:
                // Fallback: return TXT with serialized rdata so clients still see something
                return new TXTRecord(name, DClass.IN, ttl, rdata == null ? "" : rdata);
        }
    }

    /**
     * Canonicalize a name string to ensure it's a single-trailing-dot absolute name.
     */
    private String canonicalize(String name) {
        if (name == null) return null;
        name = name.trim();
        while (name.endsWith("..")) name = name.substring(0, name.length() - 1);
        if (!name.endsWith(".")) name = name + ".";
        return name;
    }

    /**
     * Safely fetch a string field from a command map.
     */
    private String getString(Map<String, Object> map, String key) {
        Object o = map.get(key);
        return o == null ? null : o.toString();
    }

    private long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object o = map.get(key);
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("Invalid numeric value for " + key + ": " + o);
        }
    }

    /**
     * Hook for verifying an owner's signature or authorization.
     * Default implementation returns true (no verification).
     */
    private boolean verifyOwnerSignature(Map<String, Object> cmd, PublicKey owner) {
        // TODO: implement using SignatureUtil in your org.ddns.bc package.
        return true;
    }
}
