package org.ddns.web.services.config;

import com.google.gson.Gson;
import org.ddns.node.NodesManager;
import org.ddns.web.user.SessionManager;
import org.ddns.web.user.User;
import spark.Request;
import spark.Response;

import java.util.Map;

/**
 * Handles graceful node exit.
 *
 * <p>
 * POST /node/exit — verifies the user's password, sends DELETE_NODE to the
 * bootstrap node (which broadcasts the removal to all peers), then clears the
 * local session so the UI returns to the setup chooser.
 */
public class ExitHandler {

    private final Gson gson = new Gson();

    public Object exit(Request request, Response response) {
        // Only available in NODE mode
        if (AppModeStore.getMode() != AppMode.NODE) {
            response.status(403);
            return Map.of("error", "Exit is only available in chain node mode.");
        }

        ExitRequest exitRequest = gson.fromJson(request.body(), ExitRequest.class);
        if (exitRequest == null || exitRequest.password == null || exitRequest.password.isBlank()) {
            response.status(400);
            return Map.of("error", "Password is required to confirm node exit.");
        }

        // Verify the password against the stored credentials
        if (!User.verifyCredentials(getUsername(), exitRequest.password.trim())) {
            response.status(401);
            return Map.of("error", "Incorrect password. Node exit aborted.");
        }

        // Send DELETE_NODE to bootstrap — the bootstrap removes this node from
        // BootstrapDB and broadcasts the deletion to all remaining peers, which
        // each remove the node from their local DBs and queues.
        try {
            NodesManager.sendDeleteNodeRequest();
        } catch (Exception e) {
            response.status(500);
            return Map.of("error", "Failed to send exit request to bootstrap: " + e.getMessage());
        }

        // Clear session so the browser is returned to the setup chooser
        SessionManager.clearSession(response);

        response.type("application/json");
        return Map.of("status", "ok", "message", "Node exit successful.");
    }

    /** Returns the username of the configured web user, or empty string if none. */
    private static String getUsername() {
        User user = User.getUser();
        return user != null ? user.getUsername() : "";
    }

    private static class ExitRequest {
        String password;
    }
}
