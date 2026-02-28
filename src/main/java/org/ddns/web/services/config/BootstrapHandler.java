package org.ddns.web.services.config;

import org.ddns.constants.ConfigKey;
import org.ddns.db.DBUtil;
import org.ddns.node.NodeConfig;
import org.ddns.node.NodesManager;
import spark.Request;
import spark.Response;

import java.util.Map;
import java.util.Set;

/**
 * Decides the next onboarding step after join/fetch synchronization.
 */
public class BootstrapHandler {

    public Object handle(Request request, Response response) {
        NodesManager.sync();

        NodeConfig selfNode = DBUtil.getInstance().getSelfNode();
        Set<NodeConfig> knownNodes = DBUtil.getInstance().getAllNodes();

        boolean isFirstNode = selfNode != null
                && (knownNodes.isEmpty() || (knownNodes.size() == 1 && knownNodes.contains(selfNode)));

        boolean requiresElection = true;
        if (isFirstNode) {
            DBUtil.getInstance().putInt(ConfigKey.IS_ACCEPTED.key(), 1);
            requiresElection = false;
        } else if (selfNode != null) {
            requiresElection = !knownNodes.contains(selfNode);
        }

        boolean isAccepted = DBUtil.getInstance().getInt(ConfigKey.IS_ACCEPTED.key(), 0) == 1;

        response.type("application/json");
        return Map.of(
                "election", requiresElection,
                "firstNode", isFirstNode,
                "accepted", isAccepted
        );
    }
}
