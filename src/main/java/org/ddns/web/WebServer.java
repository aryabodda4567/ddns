package org.ddns.web;

import com.google.gson.Gson;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ddns.web.services.config.BootstrapHandler;
import org.ddns.web.services.config.JoiningHandler;
import org.ddns.web.services.dns.DnsWebHandler;
import org.ddns.web.services.election.ElectionHandler;

import java.security.Security;

import static spark.Spark.*;

public class WebServer {

    private static final Gson gson = new Gson();
    private static final JoiningHandler joiningHandler = new JoiningHandler();
    private static final BootstrapHandler bootstrapHandler = new BootstrapHandler();


    public static void start() {

        port(8080);
        ipAddress("127.0.0.1");
        staticFiles.location("/public");

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
