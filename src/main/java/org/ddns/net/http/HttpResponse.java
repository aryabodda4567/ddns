package org.ddns.net.http;


import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;


public class HttpResponse {
    private final OutputStream out;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private int status = 200;
    private boolean headersSent = false;


    public HttpResponse(OutputStream out) {
        this.out = out;
// sensible defaults
        headers.put("Server", "SimpleHTTP/1.0");
        headers.put("Connection", "close");
        headers.put("Content-Type", "text/plain; charset=utf-8");
    }


    public void setStatus(int status) {
        this.status = status;
    }

    public void setHeader(String k, String v) {
        headers.put(k, v);
    }


    private void sendHeaders() throws IOException {
        if (headersSent) return;
        PrintWriter pw = new PrintWriter(out, false, StandardCharsets.UTF_8);
        pw.printf("HTTP/1.1 %d %s\r\n", status, StatusCodes.reason(status));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            pw.printf("%s: %s\r\n", e.getKey(), e.getValue());
        }
        pw.print("\r\n");
        pw.flush();
        headersSent = true;
    }


    public void write(String data) throws IOException {
        sendHeaders();
        out.write(data.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }


    public void write(byte[] bytes) throws IOException {
        sendHeaders();
        out.write(bytes);
        out.flush();
    }


    public void send(int status, String bodyText) throws IOException {
        setStatus(status);
        byte[] bytes = bodyText.getBytes(StandardCharsets.UTF_8);
        setHeader("Content-Length", String.valueOf(bytes.length));
        setHeader("Content-Type", "text/plain; charset=utf-8");
        sendHeaders();
        out.write(bytes);
        out.flush();
    }


    public void sendJson(int status, String json) throws IOException {
        setStatus(status);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        setHeader("Content-Length", String.valueOf(bytes.length));
        setHeader("Content-Type", "application/json; charset=utf-8");
        sendHeaders();
        out.write(bytes);
        out.flush();
    }
}