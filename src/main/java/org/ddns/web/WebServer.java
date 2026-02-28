package org.ddns.web;

import com.google.gson.Gson;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.web.services.config.BootstrapHandler;
import org.ddns.web.services.config.JoiningHandler;
import org.ddns.web.services.config.infos;
import org.ddns.web.services.dns.DnsWebHandler;
import org.ddns.web.services.election.ElectionHandler;
import org.ddns.web.user.AuthHandler;
import org.ddns.web.user.SessionManager;

import java.security.Security;
import java.util.Set;

import static spark.Spark.*;

public class WebServer {

    private static final Gson gson = new Gson();
    private static final JoiningHandler joiningHandler = new JoiningHandler();
    private static final BootstrapHandler bootstrapHandler = new BootstrapHandler();
    private static final AuthHandler authHandler = new AuthHandler();
    private static final infos infoService = new infos();


    public static void start() {

        port(8080);
        ipAddress("0.0.0.0");
        staticFiles.location("/public");

        final Set<String> publicPaths = Set.of(
                "/join.html",
                "/join",
                "/checkfetchresult",
                "/join_result.html",
                "/election/result",
                "/login.html",
                "/auth/login",
                "/auth/session",
                "/auth/logout"
        );

        before((req, res) -> {
            String path = req.pathInfo();
            if (path == null) return;

            if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")) return;
            if (publicPaths.contains(path)) return;
            if (!infoService.isAccepted()) return;
            if (SessionManager.isSessionValid(req)) return;

            if ("GET".equalsIgnoreCase(req.requestMethod()) && path.endsWith(".html")) {
                res.redirect("/login.html");
                halt(302);
                return;
            }

            res.status(401);
            res.type("application/json");
            res.body("{\"error\":\"Session expired. Please login again.\"}");
            halt(401);
        });

        exception(IllegalArgumentException.class, (e, req, res) -> {
            res.status(400);
            res.type("application/json");
            res.body("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        });

        exception(Exception.class, (e, req, res) -> {
            e.printStackTrace();
            res.status(500);
            res.type("application/json");
            res.body("{\"error\":\"Internal server error\"}");
        });


        post("/join", joiningHandler::handle, gson::toJson);

        get("/checkfetchresult", bootstrapHandler::handle, gson::toJson);
        post("/auth/login", authHandler::login, gson::toJson);
        get("/auth/session", authHandler::session, gson::toJson);
        post("/auth/logout", authHandler::logout, gson::toJson);


        ElectionHandler electionHandler = new ElectionHandler();
        post("/election/create-join", electionHandler::createJoinElection, gson::toJson);
        get("/election/nominations", electionHandler::listNominations, gson::toJson);
        post("/election/vote", electionHandler::castVote, gson::toJson);
        post("/election/result", electionHandler::electionResult, gson::toJson);


        DnsWebHandler dnsHandler = new DnsWebHandler();

        post("/dns/create", dnsHandler::create, gson::toJson);
        post("/dns/delete", dnsHandler::delete, gson::toJson);
        post("/dns/update", dnsHandler::update, gson::toJson);


        get("/dns/lookup", dnsHandler::lookup, gson::toJson);
        get("/dns/reverse", dnsHandler::reverse, gson::toJson);
        get("/dns/status", dnsHandler::status, gson::toJson);


    }


    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());
        start();
    }
}
