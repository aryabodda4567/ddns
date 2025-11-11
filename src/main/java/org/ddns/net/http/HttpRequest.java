package org.ddns.net.http;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;


public class HttpRequest {
    private final HttpMethod method;
    private final String path;
    private final String rawPath;
    private final String httpVersion;
    private final Map<String, String> headers;
    private final Map<String, String> queryParams;
    private final InputStream body;


    public HttpRequest(HttpMethod method, String rawPath, String httpVersion,
                       Map<String, String> headers, Map<String, String> queryParams, InputStream body) {
        this.method = method;
        this.rawPath = rawPath;
        this.httpVersion = httpVersion;
        this.headers = headers != null ? headers : Collections.emptyMap();
        this.queryParams = queryParams != null ? queryParams : Collections.emptyMap();
        this.body = body;
// extract path without query
        int q = rawPath.indexOf('?');
        this.path = q >= 0 ? rawPath.substring(0, q) : rawPath;
    }


    public HttpMethod getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getRawPath() {
        return rawPath;
    }

    public String getHttpVersion() {
        return httpVersion;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public InputStream getBody() {
        return body;
    }
}