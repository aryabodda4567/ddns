package org.ddns.web.services.config;

import com.google.gson.Gson;
import org.ddns.bc.SignatureUtil;
import org.ddns.chain.Wallet;
import org.ddns.constants.Role;
import org.ddns.db.BootstrapDB;
import org.ddns.db.DBUtil;
import org.ddns.node.NodeConfig;
import org.ddns.util.NetworkUtility;
import spark.Request;
import spark.Response;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddns.constants.ConfigKey;

/**
 * Handles web mode selection and bootstrap-only setup/list endpoints.
 */
public class ModeHandler {

    private final Gson gson = new Gson();

    public Object getMode(Request request, Response response) {
        response.type("application/json");
        return Map.of("mode", AppModeStore.getMode().name());
    }

    public Object selectMode(Request request, Response response) {
        ModeRequest modeRequest = gson.fromJson(request.body(), ModeRequest.class);
        if (modeRequest == null || modeRequest.mode == null) {
            response.status(400);
            return Map.of("error", "Mode is required");
        }

        AppMode selected = AppMode.from(modeRequest.mode);
        if (selected == AppMode.UNSET) {
            response.status(400);
            return Map.of("error", "Invalid mode. Use BOOTSTRAP or NODE");
        }

        AppModeStore.setMode(selected);
        // Persist simple flag in DB for bootstrap selection (1) vs node (0)
        DBUtil.getInstance().putInt(ConfigKey.BOOTSTRAP_FLAG.key(), selected == AppMode.BOOTSTRAP ? 1 : 0);

        response.type("application/json");
        return Map.of("status", "ok", "mode", selected.name());
    }

    public Object bootstrapStatus(Request request, Response response) {
        boolean configured = AppModeStore.getMode() == AppMode.BOOTSTRAP;
        try {
            configured = configured && DBUtil.getInstance().getPublicKey() != null;
        } catch (Exception ignored) {
            configured = false;
        }
        response.type("application/json");
        return Map.of(
                "mode", AppModeStore.getMode().name(),
                "configured", configured);
    }

    /**
     * Returns the stored public key and bootstrap IP for display on the bootstrap
     * page.
     * Only valid when the node is already configured as a bootstrap node.
     */
    public Object bootstrapInfo(Request request, Response response) {
        response.type("application/json");
        if (AppModeStore.getMode() != AppMode.BOOTSTRAP) {
            response.status(403);
            return Map.of("error", "Not in bootstrap mode");
        }
        try {
            PublicKey publicKey = DBUtil.getInstance().getPublicKey();
            if (publicKey == null) {
                response.status(404);
                return Map.of("error", "Bootstrap not configured yet");
            }
            String publicKeyStr = SignatureUtil.getStringFromKey(publicKey);
            String bootstrapIp = DBUtil.getInstance().getBootstrapIp();
            return Map.of(
                    "publicKey", publicKeyStr != null ? publicKeyStr : "",
                    "bootstrapIp", bootstrapIp != null ? bootstrapIp : "");
        } catch (Exception e) {
            response.status(500);
            return Map.of("error", "Failed to retrieve bootstrap info");
        }
    }

    /**
     * Automatically generates a keypair and configures this node as a Bootstrap
     * node.
     * No private key input is required from the user.
     */
    public Object setupBootstrap(Request request, Response response) throws Exception {
        // Auto-generate a fresh keypair — no user input needed
        KeyPair keyPair = Wallet.getKeyPair();
        PublicKey publicKey = keyPair.getPublic();

        String localIp = NetworkUtility.getLocalIpAddress();
        NodeConfig selfNode = new NodeConfig(localIp, Role.BOOTSTRAP, publicKey);

        DBUtil.getInstance().saveKeys(publicKey, keyPair.getPrivate());
        DBUtil.getInstance().setSelfNode(selfNode);
        DBUtil.getInstance().saveBootstrapIp(localIp);

        // Do NOT register the bootstrap node as a chain node participant.
        // BootstrapDB only tracks real chain nodes that join the network.
        AppModeStore.setMode(AppMode.BOOTSTRAP);

        String publicKeyStr = SignatureUtil.getStringFromKey(publicKey);

        response.type("application/json");
        return Map.of(
                "status", "ok",
                "mode", AppMode.BOOTSTRAP.name(),
                "localIp", localIp,
                "publicKey", publicKeyStr,
                "role", Role.BOOTSTRAP.name());
    }

    public Object listBootstrapNodes(Request request, Response response) {
        Set<NodeConfig> nodesToInform = BootstrapDB.getInstance().getAllNodes();
        List<Map<String, String>> rows = new ArrayList<>();

        for (NodeConfig node : nodesToInform) {
            String publicKey;
            try {
                publicKey = SignatureUtil.getStringFromKey(node.getPublicKey());
            } catch (Exception ex) {
                publicKey = "";
            }

            rows.add(Map.of(
                    "ip", node.getIp() == null ? "" : node.getIp(),
                    "role", node.getRole() == null ? "" : node.getRole().name(),
                    "publicKey", publicKey));
        }

        response.type("application/json");
        return Map.of("nodes", rows, "count", rows.size());
    }

    private static class ModeRequest {
        String mode;
    }
}
