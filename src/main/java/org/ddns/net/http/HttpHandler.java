package org.ddns.net.http;

import java.io.IOException;

/**
 * Implement this interface to handle incoming HTTP requests.
 */
public interface HttpHandler {
    void handle(HttpRequest req, HttpResponse res) throws IOException;
}