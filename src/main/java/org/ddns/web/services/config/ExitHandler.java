package org.ddns.web.services.config;

import com.google.gson.Gson;
import org.ddns.constants.FileNames;
import org.ddns.db.DBUtil;
import org.ddns.node.NodesManager;
import org.ddns.web.user.SessionManager;
import org.ddns.web.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.File;
import java.util.Map;

/**
 * Handles graceful node exit with a full factory reset.
 *
 * <p>
 * POST /node/exit — verifies the user's password, sends DELETE_NODE to the
 * bootstrap node (which broadcasts the removal to all peers), then wipes all
 * local state so the node restarts clean as UNSET.
 *
 * <p>
 * Reset steps:
 * <ol>
 * <li>Send DELETE_NODE to bootstrap (peers remove this node too)</li>
 * <li>Clear all DBUtil tables (config, nodes, nominations)</li>
 * <li>Delete all binary db files (dns.bin, block.bin, transaction.bin, …)</li>
 * <li>Reset AppMode to UNSET (persisted in utility.db, which was just
 * wiped)</li>
 * <li>Clear the web session cookie</li>
 * </ol>
 */
public class ExitHandler {

    private static final Logger log = LoggerFactory.getLogger(ExitHandler.class);
    private final Gson gson = new Gson();

    /** All data files that must be deleted to reset the node to a clean state. */
    private static final String[] DATA_FILES = {
            FileNames.DNS_DB,
            FileNames.BLOCK_DB,
            FileNames.BLOCK_DB_TEMP,
            FileNames.TRANSACTION_DB,
    };

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

        // Verify password against stored credentials
        if (!User.verifyCredentials(getUsername(), exitRequest.password.trim())) {
            response.status(401);
            return Map.of("error", "Incorrect password. Node exit aborted.");
        }

        // ── Step 1: Notify bootstrap + peers ─────────────────────────────────
        try {
            NodesManager.sendDeleteNodeRequest();
            log.info("[ExitHandler] DELETE_NODE sent to bootstrap.");
        } catch (Exception e) {
            // Log but don't abort — we still want to reset locally even if
            // the bootstrap is unreachable (node may be isolated).
            log.warn("[ExitHandler] Could not notify bootstrap: " + e.getMessage());
        }

        // ── Step 2: Wipe all in-DB state ─────────────────────────────────────
        try {
            DBUtil.getInstance().clearAllStorage();
            log.info("[ExitHandler] All DB tables cleared.");
        } catch (Exception e) {
            log.warn("[ExitHandler] Error clearing DB tables: " + e.getMessage());
        }

        // ── Step 3: Delete all binary data files ─────────────────────────────
        for (String fileName : DATA_FILES) {
            File f = new File(fileName);
            if (f.exists()) {
                boolean deleted = f.delete();
                log.info("[ExitHandler] " + (deleted ? "Deleted" : "Failed to delete") + " file: " + fileName);
            }
        }

        // Also attempt to delete the snapshots directory recursively
        deleteDirectory(new File(FileNames.SNAPSHOT_DIR));

        // ── Step 4: Reset AppMode to UNSET ───────────────────────────────────
        // clearAllStorage() already wiped config_store so the mode is now null
        // (which AppModeStore.getMode() treats as UNSET). Explicitly set to be safe.
        AppModeStore.setMode(AppMode.UNSET);
        log.info("[ExitHandler] AppMode reset to UNSET.");

        // ── Step 5: Clear web session ─────────────────────────────────────────
        SessionManager.clearSession(response);

        response.type("application/json");
        String body = "{\"status\":\"ok\",\"message\":\"Node exited and reset to factory state.\"}";

        // ── Step 6: Shut down the JVM ─────────────────────────────────────────
        // Delay just long enough for Spark to flush the HTTP response before exit.
        Thread shutdown = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            log.info("[ExitHandler] Shutting down node process.");
            System.exit(0);
        });
        shutdown.setDaemon(true);
        shutdown.setName("node-exit-shutdown");
        shutdown.start();

        return body;
    }

    /** Recursively deletes a directory and all its contents. */
    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists())
            return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children)
                    deleteDirectory(child);
            }
        }
        boolean deleted = dir.delete();
        log.info("[ExitHandler] " + (deleted ? "Deleted" : "Failed to delete") + ": " + dir.getPath());
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
