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

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;

/**
 * Handles the join request by storing bootstrap info, keys and web credentials,
 * then triggering node fetch/sync bootstrap logic.
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
        String privateKeyInput = joinRequest.privateKey;
        String username = joinRequest.username;
        String password = joinRequest.password;

        JoinRequestValidator.validate(bootstrapIp, privateKeyInput, username, password);

        bootstrapIp = bootstrapIp.trim();
        privateKeyInput = privateKeyInput.trim();
        username = username.trim();
        password = password.trim();

        DBUtil.getInstance().saveBootstrapIp(bootstrapIp);

        PrivateKey privateKey = SignatureUtil.getPrivateKeyFromString(privateKeyInput);
        PublicKey publicKey = Wallet.getPublicKeyFromPrivateKey(privateKey);

        LOG.info("Public key: {}", publicKey);
        DBUtil.getInstance().saveKeys(publicKey, privateKey);

        String localIp = NetworkUtility.getLocalIpAddress();
        DBUtil.getInstance().setSelfNode(new NodeConfig(localIp, Role.NONE, publicKey));

        User.saveUser(User.fromCredentials(username, password));
        long expiresAt = SessionManager.createSession(response);

        response.type("application/json");

        try {
            NodesManager.createFetchRequest();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to initiate fetch request to Bootstrap node: " + e.getMessage());
        }

        return Map.of(
                "status", "ok",
                "publicKey", publicKey.toString(),
                "localIp", localIp,
                "username", username,
                "expiresAt", expiresAt,
                "sessionSeconds", SessionManager.SESSION_DURATION_SECONDS
        );
    }
}
