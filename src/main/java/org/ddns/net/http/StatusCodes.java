package org.ddns.net.http;

public class StatusCodes {
    public static String reason(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 201:
                return "Created";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 500:
                return "Internal Server Error";
            case 501:
                return "Not Implemented";
            default:
                return "";
        }
    }
}
