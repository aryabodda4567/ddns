package org.ddns.web.services.config;

import org.ddns.db.DBUtil;
import org.ddns.node.NodeConfig;
import org.ddns.node.NodesManager;
import spark.Request;
import spark.Response;

import java.util.Map;

public class BootstrapHandler {

    public Object handle(Request req, Response res) {

        NodeConfig self = DBUtil.getInstance().getSelfNode();
        boolean election = true;

        if (self != null) {
            election = !DBUtil.getInstance().getAllNodes().contains(self);
        }

        NodesManager.sync();
        res.type("application/json");

        return Map.of("election", election);
    }
}
