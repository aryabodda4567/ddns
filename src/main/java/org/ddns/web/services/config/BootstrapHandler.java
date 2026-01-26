package org.ddns.web.services.config;

import org.ddns.constants.Role;
import org.ddns.db.DBUtil;
import spark.Request;
import spark.Response;

import java.util.Map;

public class BootstrapHandler {

    public Object handle(Request req, Response res) {

        Role role = DBUtil.getInstance().getRole();

        boolean election;

        election = role == null || role.equals(Role.NONE);

        res.type("application/json");

        return Map.of("election", election);
    }
}
