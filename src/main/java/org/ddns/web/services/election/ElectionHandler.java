package org.ddns.web.services.election;

import org.ddns.bc.SignatureUtil;
import org.ddns.constants.ElectionType;
import org.ddns.governance.Election;
import org.ddns.governance.Nomination;
import org.ddns.node.NodesManager;
import org.ddns.util.ConversionUtil;
import spark.Request;
import spark.Response;

import java.util.*;

public class ElectionHandler {



    // Inject these from your app
    public ElectionHandler() {
    }

    // ================================
    // Create Join Election
    // ================================
    public Object createJoinElection(Request req, Response res) {

        CreateElectionRequest body = Json.body(req, CreateElectionRequest.class);
        ElectionType electionType = (body.electionType==0)?ElectionType.JOIN:ElectionType.PROMOTE;
        int result = Election.createElection(
                body.password,
                body.nodeName,
                body.timeMinutes,
                body.description,
                electionType
        );

        if (result == Election.INVALID_INPUT) {
            return error("Invalid password");
        }
        else if (result == Election.INVALID_DESCRIPTION) {
            return error("Invalid description");
        }
        else if (result == Election.INVALID_TIME) {
            return error("Invalid time");
        }
        else if (result == Election.INVALID_NODE_NAME) {
            return error("Invalid node name");
        }
        else if (result == Election.ELECTION_CREATED) {
            return ok("Election created");
        }
        else {
            return error("Unknown error");
        }

    }

    // ================================
    // List nominations
    // ================================
    public Object listNominations(Request req, Response res) {

        List<Nomination> list = new ArrayList<>(Election.getNominations());

        List<Map<String, Object>> out = new ArrayList<>();

        int i = 0;
        for (Nomination n : list) {
            out.add(Map.of(
                    "index", i++,
                    "nodeIp", n.getNodeConfig().getIp(),
                    "role", n.getNodeConfig().getRole().toString(),
                    "vote", n.getVote(),
                    "startTime", n.getStartTime(),
                    "expireTime", n.getExpireTime(),
                    "electionType", n.getElectionType().toString(),
                    "nodeName", n.getNodeName(),
                    "description", n.getDescription(),
                    "publicKey", Base64.getEncoder().encodeToString(
                            n.getNodeConfig().getPublicKey().getEncoded()
                    )
            ));

        }

        return Map.of("nominations", out);
    }

    // ================================
    // Cast Vote
    // ================================
    public Object castVote(Request req, Response res) {

        VoteRequest body = Json.body(req, VoteRequest.class);

        List<Nomination> nominationList = new ArrayList<>(Election.getNominations());

        if (nominationList.isEmpty())
            return error("No nominations available");

        if (body.nominationIndex < 0 || body.nominationIndex >= nominationList.size())
            return error("Index out of range");

        try {
            Election.casteVote(nominationList.get(body.nominationIndex));
            return ok("Vote cast successfully");
        } catch (Exception e) {
            return error("Failed to cast vote: " + e.getMessage());
        }
    }

    // ================================
    // Election Result
    // ================================
    public Object joinElectionResult(Request req, Response res) throws Exception {

        ElectionResultRequest body = Json.body(req, ElectionResultRequest.class);

        int result = Election.getResult(body.password);

        if (result == Election.INVALID_INPUT)
            return error("Invalid input");

        if (result == Election.WRONG_PASSWORD)
            return error("Wrong password");

        if (result == Election.ACCEPTED) {
            NodesManager.setupNormalNode();
            NodesManager.createSyncRequest();
            return Map.of("accepted", true);
        }

        if (result == Election.REJECTED) {
            return Map.of("accepted", false);
        }

        return Map.of("No election found ",false);
    }

    public Object promoteElectionResult(Request req, Response res) throws Exception {

        ElectionResultRequest body = Json.body(req, ElectionResultRequest.class);

        int result = Election.getResult(body.password);

        if (result == Election.INVALID_INPUT)
            return error("Invalid input");

        if (result == Election.WRONG_PASSWORD)
            return error("Wrong password");

        if (result == Election.ACCEPTED) {
            NodesManager.createPromoteRequest();
            return Map.of("accepted", true);
        }

        if (result == Election.REJECTED) {
            return Map.of("accepted", false);
        }

        return Map.of("No election found ",false);
    }

    // ================================
    // Helpers
    // ================================
    private Map<String, Object> ok(String msg) {
        return Map.of("status", "ok", "message", msg);
    }

    private Map<String, Object> error(String msg) {
        return Map.of("error", msg);
    }
}
