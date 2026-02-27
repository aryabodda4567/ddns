package org.ddns.web.services.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.ddns.bc.SignatureUtil;
import org.ddns.chain.Wallet;
import org.ddns.constants.Role;
import org.ddns.db.DBUtil;
import org.ddns.node.NodeConfig;
import org.ddns.node.NodesManager;
import org.ddns.util.NetworkUtility;
import spark.Request;
import spark.Response;

import com.google.gson.Gson;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;


public class JoiningHandler {

    private static final Logger log = LoggerFactory.getLogger(JoiningHandler.class);

    private final Gson gson = new Gson();


    public Object handle(Request req, Response res) throws Exception {

        JoinRequest body = gson.fromJson(req.body(), JoinRequest.class);

        if (body == null)
            throw new IllegalArgumentException("Invalid JSON body");

        String bootstrapNodeIp = body.bootstrapIp;
        String privateKeyString = body.privateKey;

        JoinRequestValidator.validate(bootstrapNodeIp, privateKeyString);

// Normalize
        bootstrapNodeIp = bootstrapNodeIp.trim();
        privateKeyString = privateKeyString.trim();

        if (bootstrapNodeIp == null || bootstrapNodeIp.trim().isEmpty())
            throw new IllegalArgumentException("No bootstrap IP entered");

        if (privateKeyString == null || privateKeyString.trim().isEmpty())
            throw new IllegalArgumentException("No private key entered");

        bootstrapNodeIp = bootstrapNodeIp.trim();

        DBUtil.getInstance().saveBootstrapIp(bootstrapNodeIp);

        // Convert and save keys
        PrivateKey privateKey = SignatureUtil.getPrivateKeyFromString(privateKeyString);
        PublicKey publicKey = Wallet.getPublicKeyFromPrivateKey(privateKey);

        log.info("Public key: " + publicKey);
        DBUtil.getInstance().saveKeys(publicKey, privateKey);

        // set self node using local IP
        String localIp = NetworkUtility.getLocalIpAddress();
        DBUtil.getInstance().setSelfNode(new NodeConfig(localIp, Role.NONE, publicKey));

        // respond
        res.type("application/json");

        try {
            NodesManager.createFetchRequest();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to initiate fetch request to Bootstrap node: " + e.getMessage());
        }



        return Map.of(
                "status", "ok",
                "publicKey", publicKey.toString(),
                "localIp", localIp
        );
    }


}
