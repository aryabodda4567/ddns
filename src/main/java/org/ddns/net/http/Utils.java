package org.ddns.net.http;


import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;


public class Utils {
    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        String q = raw;
        int qi = q.indexOf('?');
        if (qi >= 0) q = q.substring(qi + 1);
        for (String part : q.split("[&]")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            try {
                if (eq >= 0) {
                    String k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
                    String v = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                    out.put(k, v);
                } else {
                    out.put(URLDecoder.decode(part, StandardCharsets.UTF_8), "");
                }
            } catch (Exception e) {
// ignore malformed
            }
        }
        return out;
    }


    public static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean gotCR = false;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                gotCR = true;
                continue;
            }
            if (c == '\n') break;
            if (gotCR) {
                sb.append('\r');
                gotCR = false;
            }
            sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }
}
