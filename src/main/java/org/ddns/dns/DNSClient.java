package org.ddns.dns;

import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * DNSClient - convenience wrapper around dnsjava's {@link Resolver} to:
 * <ul>
 *   <li>Perform standard lookups and reverse lookups</li>
 *   <li>Send management commands encoded as Base64-URL in sublabels of a DNS name:
 *       <pre>_cmd.&lt;chunk1&gt;.&lt;chunk2&gt;...&lt;origin&gt;</pre>
 *   </li>
 * </ul>
 * <p>
 * Logging is performed via {@link org.ddns.util.ConsolePrinter}.
 */
public class DNSClient {

    // dns label and name limits
    private static final int MAX_LABEL_LENGTH = 63;
    private static final int MAX_NAME_LENGTH = 255;

    private final SimpleResolver udpResolver; // configured for UDP (default)
    private final int timeoutSeconds;
    private final int retryAttempts;

    /**
     * Create a DNSClient with default timeout (5s) and retries (2).
     *
     * @param dnsServerIp server IP (e.g. "127.0.0.1")
     * @param port        server port (e.g. 4567)
     * @throws IOException on resolver creation errors
     */
    public DNSClient(String dnsServerIp, int port) throws IOException {
        this(dnsServerIp, port, 5, 2);
    }

    /**
     * Create a DNSClient with configurable timeout and retries.
     *
     * @param dnsServerIp    server IP (e.g. "127.0.0.1")
     * @param port           server port (e.g. 4567)
     * @param timeoutSeconds resolver socket timeout (seconds)
     * @param retryAttempts  number of attempts for the management command (UDP primary + retries)
     * @throws IOException on resolver creation errors
     */
    public DNSClient(String dnsServerIp, int port, int timeoutSeconds, int retryAttempts) throws IOException {
        if (dnsServerIp == null || dnsServerIp.isBlank()) throw new IllegalArgumentException("dnsServerIp required");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("port out of range");
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be > 0");
        if (retryAttempts < 1) throw new IllegalArgumentException("retryAttempts must be >= 1");

        this.timeoutSeconds = timeoutSeconds;
        this.retryAttempts = retryAttempts;

        SimpleResolver sr = new SimpleResolver(dnsServerIp);
        sr.setPort(port);
        sr.setTimeout(timeoutSeconds);
        this.udpResolver = sr;
        ConsolePrinter.printInfo(String.format("DNSClient initialized for %s:%d timeout=%ds retries=%d",
                dnsServerIp, port, timeoutSeconds, retryAttempts));
    }

    /**
     * Standard lookup wrapper.
     *
     * @param name domain name (e.g. "example.com.") — either with or without trailing dot.
     * @param type dns type from {@link Type} (e.g. {@link Type#A})
     * @return Record[] array or null when no records found
     * @throws IOException        if network IO fails
     * @throws TextParseException if name is invalid
     */
    public Record[] lookup(String name, int type) throws IOException, TextParseException {
        String canon = canonicalize(name);
        Lookup lookup = new Lookup(canon, type);
        lookup.setResolver(udpResolver);
        return lookup.run();
    }

    /**
     * Reverse (PTR) lookup for an IP address string (IPv4/IPv6).
     *
     * @param ip text form IPv4 or IPv6 (e.g. "1.2.3.4" or "2001:db8::1")
     * @return Record[] PTR answers or null
     * @throws IOException        on IO errors
     * @throws TextParseException if built PTR name is invalid
     */
    public Record[] reverseLookup(String ip) throws IOException, TextParseException {
        Name ptrName = ReverseMap.fromAddress(ip);
        Lookup lookup = new Lookup(ptrName, Type.PTR);
        lookup.setResolver(udpResolver);
        return lookup.run();
    }

    /**
     * Send a management command to the server by encoding the JSON representation of the command
     * as Base64-URL (no padding), splitting it into DNS labels (<=63 chars), and forming a query
     * name `_cmd.&lt;chunk1&gt;.&lt;chunk2&gt;...&lt;origin&gt;`.
     * <p>
     * Behavior:
     * - Builds query name & validates label / name lengths
     * - Sends a TXT query via UDP first. If the response header has the TC flag set (truncated),
     * or if UDP fails, retries via TCP resolver
     * - Retries up to retryAttempts
     *
     * @param cmdMap map representing your JSON command (will be converted with {@link ConversionUtil}).
     * @param origin zone/origin (e.g. "example.com." or "example.com") — will be canonicalized.
     * @return first DNS answer Record from ANSWER section (often a TXT record with "OK: ..." text), or null if no answer.
     * @throws Exception on encoding/validation/network errors (detailed message)
     */
    public Record sendCommand(Map<String, Object> cmdMap, String origin) throws Exception {
        if (cmdMap == null) throw new IllegalArgumentException("cmdMap cannot be null");
        if (origin == null || origin.isBlank()) throw new IllegalArgumentException("origin cannot be null/blank");

        String json = ConversionUtil.toJsonString(cmdMap);
        if (json == null) throw new IllegalArgumentException("ConversionUtil.toJsonString returned null");

        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));

        List<String> chunks = splitToLabels(b64, MAX_LABEL_LENGTH);

        String canonicalOrigin = canonicalize(origin);
        StringBuilder qBuilder = new StringBuilder();
        qBuilder.append("_cmd");
        for (String chunk : chunks) {
            qBuilder.append('.').append(chunk);
        }
        qBuilder.append('.').append(canonicalOrigin);

        String qname = qBuilder.toString();

        if (qname.length() > MAX_NAME_LENGTH) {
            String msg = "Encoded command name exceeds DNS maximum length of " + MAX_NAME_LENGTH + ": length=" + qname.length();
            ConsolePrinter.printFail(msg);
            throw new IllegalArgumentException(msg);
        }

        ConsolePrinter.printInfo("sendCommand: query name length=" + qname.length());

        Name name = Name.fromString(qname);

        Record question = Record.newRecord(name, Type.TXT, DClass.IN);
        Message query = Message.newQuery(question);

        Exception lastEx = null;
        for (int attempt = 1; attempt <= this.retryAttempts; attempt++) {
            try {
                Message response = udpResolver.send(query);

                if (response.getHeader().getFlag(Flags.TC)) {
                    ConsolePrinter.printWarning("UDP response truncated (TC flag). Retrying over TCP (attempt " + attempt + "/" + retryAttempts + ")");
                    Message tcpResp = sendOverTcp(query);
                    return extractFirstAnswer(tcpResp);
                }

                return extractFirstAnswer(response);
            } catch (IOException ioe) {
                lastEx = ioe;
                ConsolePrinter.printWarning("UDP send attempt " + attempt + "/" + retryAttempts + " failed: " + ioe.getMessage());
                try {
                    Message tcpResp = sendOverTcp(query);
                    return extractFirstAnswer(tcpResp);
                } catch (Exception txEx) {
                    lastEx = txEx;
                    ConsolePrinter.printWarning("TCP fallback also failed: " + txEx.getMessage());
                }
            } catch (Exception ex) {
                lastEx = ex;
                ConsolePrinter.printWarning("sendCommand attempt " + attempt + "/" + retryAttempts + " error: " + ex.getMessage());
            }
        }

        String ngr = "sendCommand failed after " + retryAttempts + " attempts";
        ConsolePrinter.printFail(ngr + (lastEx == null ? "" : " - " + lastEx.getMessage()));
        throw new IOException(ngr, lastEx);
    }

    // --- internal helpers ---

    /**
     * Sends the query using a TCP-enabled resolver (created on-demand).
     */
    private Message sendOverTcp(Message query) throws IOException {
        // Use the udpResolver's target host
        String host = udpResolver.getAddress().getHostName();
        SimpleResolver tcpResolver = new SimpleResolver(host);
        tcpResolver.setPort(udpResolver.getPort());
        tcpResolver.setTCP(true);
        tcpResolver.setTimeout(this.timeoutSeconds);
        return tcpResolver.send(query);
    }

    /**
     * Extract first answer Record from Message ANSWER section, or null if none.
     */
    private Record extractFirstAnswer(Message response) {
        if (response == null) return null;
        Record[] answers = response.getSectionArray(Section.ANSWER);
        if (answers != null && answers.length > 0) {
            return answers[0];
        }
        return null;
    }

    /**
     * Splits a long string into DNS-compatible labels of maxLabelLen characters.
     * This method will never produce labels longer than maxLabelLen. Caller must still validate total name length.
     */
    private List<String> splitToLabels(String s, int maxLabelLen) {
        List<String> out = new ArrayList<>((s.length() + maxLabelLen - 1) / maxLabelLen);
        for (int i = 0; i < s.length(); i += maxLabelLen) {
            int end = Math.min(s.length(), i + maxLabelLen);
            out.add(s.substring(i, end));
        }
        return out;
    }

    /**
     * Ensures a name string is canonical (ends with a single dot).
     * <p>
     * Examples:
     * - "example.com" -> "example.com."
     * - "example.com." -> "example.com."
     * - "example.com.." -> "example.com."
     *
     * @param name The name to canonicalize.
     * @return The canonicalized name.
     */
    private String canonicalize(String name) {
        if (name == null) return null;
        name = name.trim();
        while (name.endsWith("..")) name = name.substring(0, name.length() - 1);
        if (!name.endsWith(".")) name = name + ".";
        return name;
    }
}
