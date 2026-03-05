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

import java.security.PrivateKey;
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
            configured = configured && DBUtil.getInstance().getPrivateKey() != null;
        } catch (Exception ignored) {
            configured = false;
        }
        response.type("application/json");
        return Map.of(
                "mode", AppModeStore.getMode().name(),
                "configured", configured);
    }

    public Object setupBootstrap(Request request, Response response) throws Exception {
        BootstrapSetupRequest setupRequest = gson.fromJson(request.body(), BootstrapSetupRequest.class);
        if (setupRequest == null || setupRequest.privateKey == null || setupRequest.privateKey.isBlank()) {
            response.status(400);
            return Map.of("error", "Private key is required");
        }

        String privateKeyInput = setupRequest.privateKey.trim();
        PrivateKey privateKey = SignatureUtil.getPrivateKeyFromString(privateKeyInput);
        PublicKey publicKey = Wallet.getPublicKeyFromPrivateKey(privateKey);

        String localIp = NetworkUtility.getLocalIpAddress();
        NodeConfig selfNode = new NodeConfig(localIp, Role.BOOTSTRAP, publicKey);

        DBUtil.getInstance().saveKeys(publicKey, privateKey);
        DBUtil.getInstance().setSelfNode(selfNode);
        DBUtil.getInstance().saveBootstrapIp(localIp);

        // Do NOT register the bootstrap node as a chain node participant.
        // BootstrapDB only tracks real chain nodes that join the network.
        AppModeStore.setMode(AppMode.BOOTSTRAP);

        response.type("application/json");
        return Map.of(
                "status", "ok",
                "mode", AppMode.BOOTSTRAP.name(),
                "localIp", localIp,
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

    private static class BootstrapSetupRequest {
        String privateKey;
    }
}
