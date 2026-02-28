package org.ddns.web.user;

import com.google.gson.Gson;
import org.ddns.web.services.config.infos;
import spark.Request;
import spark.Response;

import java.util.Map;

public class AuthHandler {

    private final Gson gson = new Gson();
    private final infos infoService = new infos();

    public Object login(Request req, Response res) {
        LoginRequest body = gson.fromJson(req.body(), LoginRequest.class);

        if (body == null || body.username == null || body.password == null) {
            res.status(400);
            return Map.of("error", "Username and password are required");
        }

        if (User.getUser() == null) {
            res.status(400);
            return Map.of("error", "No web user configured. Complete join setup first.");
        }

        if (!User.verifyCredentials(body.username, body.password)) {
            res.status(401);
            return Map.of("error", "Invalid username or password");
        }

        long expiresAt = SessionManager.createSession(res);
        res.type("application/json");

        return Map.of(
                "status", "ok",
                "expiresAt", expiresAt,
                "sessionSeconds", SessionManager.SESSION_DURATION_SECONDS
        );
    }

    public Object session(Request req, Response res) {
        boolean userConfigured = User.getUser() != null;
        boolean accepted = infoService.IsAccepted();
        boolean authenticated = !userConfigured || SessionManager.isSessionValid(req);
        long expiresAt = SessionManager.getExpiresAt();

        res.type("application/json");

        return Map.of(
                "userConfigured", userConfigured,
                "accepted", accepted,
                "requireLogin", userConfigured,
                "authenticated", authenticated,
                "expiresAt", expiresAt,
                "sessionSeconds", SessionManager.SESSION_DURATION_SECONDS
        );
    }

    public Object logout(Request req, Response res) {
        SessionManager.clearSession(res);
        res.type("application/json");
        return Map.of("status", "ok");
    }
}
