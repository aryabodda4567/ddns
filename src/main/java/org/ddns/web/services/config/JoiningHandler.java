package org.ddns.web.services.config;

import com.google.gson.Gson;
import org.ddns.bc.SignatureUtil;
import org.ddns.chain.Wallet;
import org.ddns.constants.Role;
import org.ddns.db.DBUtil;
import org.ddns.node.NodeConfig;
import org.ddns.node.NodesManager;
import org.ddns.util.NetworkUtility;
import org.ddns.web.user.SessionManager;
import org.ddns.web.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;

/**
 * Handles the join request by storing bootstrap info, auto-generating a
 * keypair,
 * saving web credentials, then triggering node fetch/sync bootstrap logic.
 */
public class JoiningHandler {

    private static final Logger LOG = LoggerFactory.getLogger(JoiningHandler.class);
    private final Gson gson = new Gson();

    public Object handle(Request request, Response response) throws Exception {
        JoinRequest joinRequest = gson.fromJson(request.body(), JoinRequest.class);
        if (joinRequest == null) {
            throw new IllegalArgumentException("Invalid JSON body");
        }

        String bootstrapIp = joinRequest.bootstrapIp;
        String username = joinRequest.username;
        String password = joinRequest.password;
        String bootstrapPublicKey = joinRequest.bootstrapPublicKey;

        JoinRequestValidator.validate(bootstrapIp, bootstrapPublicKey, username, password);

        bootstrapIp = bootstrapIp.trim();
        username = username.trim();
        password = password.trim();
        bootstrapPublicKey = bootstrapPublicKey.trim();

        DBUtil.getInstance().saveBootstrapIp(bootstrapIp);
        DBUtil.getInstance().setBootstrapNode(new NodeConfig(bootstrapIp, Role.BOOTSTRAP,
                SignatureUtil.getPublicKeyFromString(bootstrapPublicKey)));

        AppModeStore.setMode(AppMode.NODE);

        // Auto-generate a fresh keypair for this node — no private key input required
        KeyPair keyPair = Wallet.getKeyPair();
        PublicKey publicKey = keyPair.getPublic();

        LOG.info("Generated new keypair for joining node. Public key: {}", publicKey);
        DBUtil.getInstance().saveKeys(publicKey, keyPair.getPrivate());

        String localIp = NetworkUtility.getLocalIpAddress();
        DBUtil.getInstance().setSelfNode(new NodeConfig(localIp, Role.NONE, publicKey));

        User.saveUser(User.fromCredentials(username, password));
        long expiresAt = SessionManager.createSession(response);

        String publicKeyStr = SignatureUtil.getStringFromKey(publicKey);

        response.type("application/json");

        try {
            NodesManager.createFetchRequest();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to initiate fetch request to Bootstrap node: " + e.getMessage());
        }

        return Map.of(
                "status", "ok",
                "publicKey", publicKeyStr,
                "localIp", localIp,
                "username", username,
                "expiresAt", expiresAt,
                "sessionSeconds", SessionManager.SESSION_DURATION_SECONDS);
    }
}
