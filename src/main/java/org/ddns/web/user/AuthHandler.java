package org.ddns.web.user;

import com.google.gson.Gson;
import org.ddns.web.services.config.AppMode;
import org.ddns.web.services.config.AppModeStore;
import org.ddns.web.services.config.infos;
import spark.Request;
import spark.Response;

import java.util.Map;

/**
 * Authentication API for login/session/logout.
 */
public class AuthHandler {

    private final Gson gson = new Gson();
    private final infos infoService = new infos();

    public Object login(Request request, Response response) {
        if (AppModeStore.getMode() == AppMode.BOOTSTRAP) {
            response.status(403);
            return Map.of("error", "Login is disabled in bootstrap mode.");
        }

        LoginRequest loginRequest = gson.fromJson(request.body(), LoginRequest.class);

        if (loginRequest == null || loginRequest.username == null || loginRequest.password == null) {
            response.status(400);
            return Map.of("error", "Username and password are required");
        }

        if (User.getUser() == null) {
            response.status(400);
            return Map.of("error", "No web user configured. Complete join setup first.");
        }

        if (!User.verifyCredentials(loginRequest.username, loginRequest.password)) {
            response.status(401);
            return Map.of("error", "Invalid username or password");
        }

        long expiresAt = SessionManager.createSession(response);
        response.type("application/json");

        return Map.of(
                "status", "ok",
                "expiresAt", expiresAt,
                "sessionSeconds", SessionManager.SESSION_DURATION_SECONDS
        );
    }

    public Object session(Request request, Response response) {
        AppMode mode = AppModeStore.getMode();
        boolean userConfigured = User.getUser() != null;
        boolean accepted = infoService.IsAccepted();
        boolean authenticated = SessionManager.isSessionValid(request);
        long expiresAt = SessionManager.getExpiresAt();
        boolean bootstrapMode = mode == AppMode.BOOTSTRAP;
        boolean startupModeUnset = mode == AppMode.UNSET;

        response.type("application/json");

        return Map.of(
                "mode", mode.name(),
                "userConfigured", userConfigured,
                "requireJoin", !bootstrapMode && !startupModeUnset && !userConfigured,
                "accepted", accepted,
                "requireLogin", !bootstrapMode && userConfigured,
                "authenticated", bootstrapMode ? true : authenticated,
                "expiresAt", expiresAt,
                "sessionSeconds", SessionManager.SESSION_DURATION_SECONDS
        );
    }

    public Object logout(Request request, Response response) {
        SessionManager.clearSession(response);
        response.type("application/json");
        return Map.of("status", "ok");
    }
}
