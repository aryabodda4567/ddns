package org.ddns.net.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple router mapping METHOD+PATH -> handler. Supports exact paths.
 * You can extend this to support path params or regex.
 */
public class Router {
    private final Map<String, HttpHandler> routes = new ConcurrentHashMap<>();


    private String routeKey(HttpMethod m, String path) {
        return m.name() + " " + path;
    }


    public void add(HttpMethod method, String path, HttpHandler handler) {
        routes.put(routeKey(method, path), handler);
    }


    public HttpHandler match(HttpMethod method, String path) {
        HttpHandler h = routes.get(routeKey(method, path));
        if (h != null) return h;
// fallback: try any-method
        return routes.get(routeKey(HttpMethod.GET, path));
    }
}