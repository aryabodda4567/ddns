package org.ddns.web.services.config;

import org.ddns.db.DBUtil;
import org.ddns.constants.ConfigKey;
import org.ddns.node.NodeConfig;
import org.ddns.node.NodesManager;
import spark.Request;
import spark.Response;

import java.util.Map;
import java.util.Set;

public class BootstrapHandler {

    public Object handle(Request req, Response res) {

        NodesManager.sync();
        NodeConfig self = DBUtil.getInstance().getSelfNode();
        Set<NodeConfig> allNodes = DBUtil.getInstance().getAllNodes();

        boolean firstNode = self != null && (allNodes.isEmpty() || (allNodes.size() == 1 && allNodes.contains(self)));
        boolean election = true;

        if (firstNode) {
            DBUtil.getInstance().putInt(ConfigKey.IS_ACCEPTED.key(), 1);
            election = false;
        } else if (self != null) {
            election = !allNodes.contains(self);
        }

        res.type("application/json");

        return Map.of(
                "election", election,
                "firstNode", firstNode,
                "accepted", DBUtil.getInstance().getInt(ConfigKey.IS_ACCEPTED.key(), 0) == 1
        );
    }
}
