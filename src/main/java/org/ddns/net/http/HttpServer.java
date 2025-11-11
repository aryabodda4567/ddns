package org.ddns.net.http;


import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Minimal, single-file friendly HTTP server core.
 * - Thread pool for handling connections
 * - Simple request parsing
 * - Router based dispatch
 */
public class HttpServer {
    private final Router router = new Router();
    private final ExecutorService pool;
    private final int backlog;
    private volatile boolean running = false;


    public HttpServer(int threadPoolSize) {
        this(threadPoolSize, 50);
    }


    public HttpServer(int threadPoolSize, int backlog) {
        this.backlog = backlog;
        this.pool = Executors.newFixedThreadPool(threadPoolSize, new ThreadFactory() {
            private final AtomicInteger id = new AtomicInteger(1);

            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "SimpleHttpWorker-" + id.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }


    public void addHandler(HttpMethod method, String path, HttpHandler handler) {
        router.add(method, path, handler);
    }


    public void start(int port) throws IOException {
        running = true;
        ServerSocket server = new ServerSocket(port, backlog);
        System.out.println("SimpleHTTP server listening on port " + port);
        while (running) {
            final Socket client = server.accept();
            pool.submit(() -> handleClient(client));
        }
//server.close(); // unreachable unless stop implemented
    }


    public void stop() {
        running = false;
        pool.shutdownNow();
    }


    private void handleClient(Socket client) {
        try (Socket sock = client;
             InputStream raw = new BufferedInputStream(sock.getInputStream());
             OutputStream out = sock.getOutputStream()) {


// parse request line
            String requestLine = Utils.readLine(raw);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 3) {
// bad request
                HttpResponse r = new HttpResponse(out);
                r.send(400, "Bad Request");
                return;
            }
            HttpMethod method = HttpMethod.valueOf(parts[0]);
            String rawPath = parts[1];
            String httpVersion = parts[2];


// headers
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = Utils.readLine(raw)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim();
                    String v = line.substring(idx + 1).trim();
                    headers.put(k, v);
                }
            }


            Map<String, String> query = Utils.parseQuery(rawPath);


// handle body on Content-Length if present
            InputStream bodyStream = InputStream.nullInputStream();
            if (headers.containsKey("Content-Length")) {
                try {
                    int len = Integer.parseInt(headers.get("Content-Length"));
                    byte[] buf = new byte[len];
                    int read = 0;
                    while (read < len) {
                        int r = raw.read(buf, read, len - read);
                        if (r == -1) break;
                        read += r;
                    }
                    bodyStream = new ByteArrayInputStream(buf, 0, read);
                } catch (NumberFormatException ignored) {
                }
            }


            HttpRequest req = new HttpRequest(method, rawPath, httpVersion, headers, query, bodyStream);
            HttpResponse res = new HttpResponse(out);


            HttpHandler handler = router.match(method, req.getPath());
            if (handler != null) {
                try {
                    handler.handle(req, res);
                } catch (Exception e) {
                    res.setStatus(500);
                    res.setHeader("Content-Type", "text/plain; charset=utf-8");
                    res.send(500, "Internal Server Error: " + e.getMessage());
                }
            } else {
                res.setStatus(404);
                res.send(404, "Not Found");
            }


        } catch (Exception e) {
// connection-level error - log and ignore
            System.err.println("Connection handling error: " + e.getMessage());
        }
    }
}