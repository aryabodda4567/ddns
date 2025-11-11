package org.ddns.tests;

import com.google.gson.*;
import org.ddns.net.http.HttpMethod;
import org.ddns.net.http.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure console-based data test suite for your HTTP server.
 * No JUnit or frameworks — just run `java org.ddns.tests.HTTPDataConsoleTest`
 * <p>
 * ✅ Checks:
 * 1. GET /get → parses valid SModel
 * 2. POST /echo → echoes correct data
 * 3. POST /echo invalid JSON → handled safely
 * 4. POST /echo large payload → parsed correctly
 */
public class HTTPTest {

    private static final Gson gson = new GsonBuilder().create();
    private static final int PORT = 8080;
    private static HttpServer serverRef;

    // ---------- START SERVER ----------
    public static void startServer() {
        Thread t = new Thread(() -> {
            try {
                serverRef = new HttpServer(8);

                // GET /get
                serverRef.addHandler(HttpMethod.GET, "/get", (req, res) -> {
                    SModel model = SModel.random();
                    res.setHeader("Content-Type", "application/json; charset=utf-8");
                    res.send(200, gson.toJson(model));
                });

                // POST /echo
                serverRef.addHandler(HttpMethod.POST, "/echo", (req, res) -> {
                    String body;
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(req.getBody(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append('\n');
                        body = sb.toString().trim();
                    }

                    Object payload;
                    try {
                        payload = gson.fromJson(body, SModel.class);
                        SModel sm = (SModel) payload;
                        if (sm == null || sm.id == null || sm.name == null) payload = body;
                    } catch (Exception e) {
                        payload = body;
                    }

                    // Build response as a Map instead of a local class
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("method", req.getMethod().name());
                    resp.put("data", payload);
                    resp.put("message", "Echo");
                    resp.put("receivedAt", System.currentTimeMillis());

                    String respJson = gson.toJson(resp);
                    // debug log (optional)
                    System.out.println("[SERVER-ECHO] received len=" + (body == null ? 0 : body.length())
                            + " responseLen=" + (respJson == null ? 0 : respJson.length()));
                    res.setHeader("Content-Type", "application/json; charset=utf-8");
                    res.send(200, respJson);
                });


                System.out.println("✅ HTTP Test Server started on port " + PORT);
                serverRef.start(PORT);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "HTTPDataConsoleTest-Server");
        t.setDaemon(true);
        t.start();

        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
    }

    private static HttpResult sendJsonRequestRaw(String method, String urlStr, String body) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setDoInput(true);
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method)) {
            conn.setDoOutput(true);
        }
        conn.setRequestProperty("Accept", "application/json");
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
        }

        int code = conn.getResponseCode();
        InputStream in = null;
        try {
            in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        } catch (IOException ioe) {
            // getErrorStream may still be null; we'll handle below
            in = conn.getErrorStream();
        }

        StringBuilder sb = new StringBuilder();
        if (in != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
        }
        conn.disconnect();
        String bodyStr = sb.toString().trim();
        return new HttpResult(code, bodyStr);
    }

    /**
     * Returns parsed JsonObject or null when the body is empty/null.
     * Throws IOException on transport-level problems.
     */
    private static JsonObject requestJson(String method, String url, String body) throws IOException {
        HttpResult res = sendJsonRequestRaw(method, url, body);
        String raw = res.body;
        if (raw == null || raw.isEmpty()) {
            // defensive: no body
            System.err.println("[WARN] empty response body (HTTP " + res.status + ") for " + method + " " + url);
            return null;
        }
        if ("null".equals(raw)) {
            // literal null body
            System.err.println("[WARN] response body is literal 'null' (HTTP " + res.status + ") for " + method + " " + url);
            return null;
        }
        try {
            JsonElement el = gson.fromJson(raw, JsonElement.class);
            if (el == null || el.isJsonNull()) {
                System.err.println("[WARN] parsed JSON is null (raw='" + raw + "')");
                return null;
            }
            if (!el.isJsonObject()) {
                // not an object (could be primitive / array) — print and return as object wrapper
                System.err.println("[WARN] response JSON is not an object (was " + el.getClass().getSimpleName() + "): " + raw);
                JsonObject wrapper = new JsonObject();
                wrapper.add("value", el);
                wrapper.addProperty("_raw", raw);
                return wrapper;
            }
            return el.getAsJsonObject();
        } catch (JsonSyntaxException jse) {
            System.err.println("[ERROR] failed to parse JSON response (HTTP " + res.status + "): raw=" + raw);
            throw jse;
        }
    }

    // ---------- ASSERTION UTILS ----------
    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("❌ " + message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError("❌ " + message + " (expected=" + expected + ", got=" + actual + ")");
    }

    private static void pass(String msg) {
        System.out.println("✅ " + msg);
    }

    // ---------- TESTS ----------
    private static void testGetReturnsValidSModel() throws Exception {
        JsonObject obj = requestJson("GET", "http://localhost:" + PORT + "/get", null);
        assertTrue(obj != null, "GET /get must return JSON");

        SModel model = gson.fromJson(obj, SModel.class);
        assertTrue(model.id != null && !model.id.isEmpty(), "id must not be empty");
        assertTrue(model.name.startsWith("name-"), "name must start with 'name-'");
        assertTrue(model.value >= 0 && model.value < 1000, "value within range");
        assertTrue(model.ts > 0, "timestamp must be positive");

        pass("GET /get returned valid SModel ✅");
    }

    private static void testPostEchoValidModel() throws Exception {
        SModel in = SModel.random();
        JsonObject response = requestJson("POST", "http://localhost:" + PORT + "/echo", gson.toJson(in));

        assertEquals("POST", response.get("method").getAsString(), "method must be POST");
        SModel out = gson.fromJson(response.get("data"), SModel.class);

        assertEquals(in.id, out.id, "id must match");
        assertEquals(in.name, out.name, "name must match");
        assertEquals(in.value, out.value, "value must match");
        assertTrue(Math.abs(in.ts - out.ts) < 1000, "timestamp close");

        pass("POST /echo correctly echoed valid SModel ✅");
    }

    private static void testPostEchoInvalidJson() throws Exception {
        String bad = "not-json-@@@";
        JsonObject resp = requestJson("POST", "http://localhost:" + PORT + "/echo", bad);

        assertEquals("POST", resp.get("method").getAsString(), "method must be POST");
        assertTrue(resp.get("data").isJsonPrimitive(), "data should be primitive");
        assertEquals(bad, resp.get("data").getAsString(), "data should equal input raw text");

        pass("POST /echo handled invalid JSON safely ✅");
    }

    private static void testPostLargePayload() throws Exception {
        int payloadSize = 128 * 1024;
        StringBuilder sb = new StringBuilder(payloadSize);
        for (int i = 0; i < payloadSize; i++) sb.append((char) ('A' + (i % 26)));
        Map<String, Object> map = new HashMap<>();
        map.put("meta", SModel.random());
        map.put("payload", sb.toString());

        JsonObject resp = requestJson("POST", "http://localhost:" + PORT + "/echo", gson.toJson(map));
        assertEquals("POST", resp.get("method").getAsString(), "method must be POST");
        String data = resp.get("data").toString();
        assertTrue(data.length() >= payloadSize / 2, "returned data large enough");

        pass("POST /echo handled large payload ✅");
    }

    // ---------- MAIN ----------
    public static void main(String[] args) throws Exception {
        startServer();
        System.out.println("\n🚀 Starting HTTP Data Console Tests...\n");

        int passed = 0, failed = 0;
        long start = System.currentTimeMillis();

        try {
            testGetReturnsValidSModel();
            passed++;
        } catch (Throwable e) {
            failed++;
            e.printStackTrace();
        }
        try {
            testPostEchoValidModel();
            passed++;
        } catch (Throwable e) {
            failed++;
            e.printStackTrace();
        }
        try {
            testPostEchoInvalidJson();
            passed++;
        } catch (Throwable e) {
            failed++;
            e.printStackTrace();
        }
        try {
            testPostLargePayload();
            passed++;
        } catch (Throwable e) {
            failed++;
            e.printStackTrace();
        }

        long elapsed = System.currentTimeMillis() - start;

        System.out.println("\n=============================");
        System.out.println("🧩 Test Summary:");
        System.out.println("  ✅ Passed: " + passed);
        System.out.println("  ❌ Failed: " + failed);
        System.out.println("  ⏱  Time: " + elapsed + " ms");
        System.out.println("=============================\n");

        if (failed == 0) System.out.println("🎉 All data tests passed successfully!");
        else System.out.println("⚠ Some tests failed — see above output.");

        System.exit(failed == 0 ? 0 : 1);
    }

    // Simple data model
    public static class SModel {
        public String id;
        public String name;
        public int value;
        public long ts;

        public SModel() {
        }

        public SModel(String id, String name, int value, long ts) {
            this.id = id;
            this.name = name;
            this.value = value;
            this.ts = ts;
        }

        public static SModel random() {
            return new SModel(
                    UUID.randomUUID().toString(),
                    "name-" + UUID.randomUUID().toString().substring(0, 6),
                    (int) (Math.random() * 1000),
                    System.currentTimeMillis()
            );
        }
    }

    /// ---------- Robust client (replace your existing sendJsonRequest / requestJson) ----------
        private record HttpResult(int status, String body) {
    }
}
