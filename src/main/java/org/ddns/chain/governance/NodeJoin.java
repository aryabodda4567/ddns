package org.ddns.chain.governance;

import org.ddns.chain.Names;
import org.ddns.chain.Role;
import org.ddns.db.DBUtil; // Import DBUtil
import org.ddns.net.*;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;

import java.security.PublicKey;
import java.util.*;

/**
 * Handles node joining governance logic using DBUtil via the Nomination class.
 */
public class NodeJoin implements Voting, MessageHandler {

    private static final Object NODE_JOIN_LOCK = new Object();
    private static final Set<MessageType> allowedType;
    private NetworkManager networkManager;
    // --- DBUtil Instance ---
    private final DBUtil dbUtil = DBUtil.getInstance();

    static {
        allowedType = Set.of(MessageType.NOMINATION_REQUEST, MessageType.CAST_VOTE);
    }

    private NodeJoinListener listener;

    public interface NodeJoinListener { /* ... (interface remains the same) ... */
    public void onNodeJoinWin();
    public void onNodeJoinLose();
    public void onNodeJoinProgress();

    }

    @Override
    public boolean createNominationRequest(int timeLimit) {
        synchronized (NODE_JOIN_LOCK) {
            try {
                String selfIp = NetworkUtility.getLocalIpAddress();
                // Get key from DBUtil
                PublicKey publicKey = dbUtil.getPublicKey();

                if (selfIp == null || publicKey == null) {
                    ConsolePrinter.printFail("[NodeJoin]: Missing IP or Public Key from DB.");
                    return false;
                }

                Nomination nomination = new Nomination(Nomination.JOIN, selfIp, publicKey);
                Map<String, String> payload = new HashMap<>();
                payload.put("NOMINATION", ConversionUtil.toJson(nomination));

                Message message = new Message(
                        MessageType.NOMINATION_REQUEST,
                        selfIp,
                        publicKey,
                        ConversionUtil.toJson(payload)
                );

                Set<Role> roles = Set.of(Role.GENESIS, Role.LEADER_NODE);

                // Get nodes from DBUtil via Bootstrap singleton
                boolean broadcastSuccess = NetworkManager.broadcast(
                        ConversionUtil.toJson(message),
                        Bootstrap.getInstance().getNodes(), // Assumes Bootstrap uses DBUtil
                        roles
                );

                if (broadcastSuccess) {
                    // Use static Nomination method which now uses DBUtil
                    Nomination.createNomination(Nomination.JOIN, timeLimit);
                    ConsolePrinter.printSuccess("[NodeJoin]: Nomination request broadcasted successfully.");
                    return true;
                } else {
                    ConsolePrinter.printWarning("[NodeJoin]: Failed to broadcast nomination request.");
                    return false;
                }
            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Exception during createNominationRequest - " + e.getMessage());
                e.printStackTrace(); // Print stack trace
                return false;
            }
        }
    }

    @Override
    public void resolveNominationRequest(Message message, Map<String, String> payload) {
        synchronized (NODE_JOIN_LOCK) {
            try {
                if (payload == null || !payload.containsKey("NOMINATION")) { /* ... error handling ... */ return; }
                Nomination nomination = ConversionUtil.fromJson(payload.get("NOMINATION"), Nomination.class);
                if (nomination == null || nomination.getPublicKey() == null) { // Check PublicKey
                    ConsolePrinter.printWarning("[NodeJoin]: Failed to parse nomination or missing public key.");
                    return;
                }
                // Use static Nomination method which now uses DBUtil
                Nomination.addNomination(nomination);
                ConsolePrinter.printInfo("[NodeJoin]: Nomination resolved successfully for " + nomination.getIpAddress());
//                ///
//            ///
//            ///
//            /// Testing
//                createCastVoteRequest(true,nomination);
//            /// Testing
//            ///

            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Error resolving nomination request - " + e.getMessage());
            }
        }
    }

    @Override
    public boolean createCastVoteRequest(boolean response, Nomination nomination) {
        synchronized (NODE_JOIN_LOCK) {
            if (nomination == null) { /* ... error handling ... */ return false; }

            // Check if already voted via DB state
            List<Nomination> currentNominations = Nomination.getNominations();
            Nomination storedNomination = currentNominations.stream()
                    .filter(n -> n.equals(nomination))
                    .findFirst().orElse(null);

            if (storedNomination != null && storedNomination.isVoted()) {
                ConsolePrinter.printInfo("[NodeJoin]: Vote already cast for " + nomination.getIpAddress());
                return true; // Indicate already done, not failure
            }
            if (storedNomination == null) {
                ConsolePrinter.printWarning("[NodeJoin]: Cannot find nomination to vote for: " + nomination.getIpAddress());
                return false;
            }


            try {
                Map<String, String> map = new HashMap<>();
                map.put("VOTE", Boolean.toString(response));
                Message message = new Message(
                        MessageType.CAST_VOTE,
                        NetworkUtility.getLocalIpAddress(),
                        dbUtil.getPublicKey(), // Get key from DBUtil
                        ConversionUtil.toJson(map)
                );
                boolean sent = NetworkManager.sendDirectMessage(
                        nomination.getIpAddress(),
                        ConversionUtil.toJson(message)
                );
                if (sent) {
                    ConsolePrinter.printSuccess("[NodeJoin]: Vote sent to " + nomination.getIpAddress());
                    // Use static Nomination method which now uses DBUtil
                    Nomination.markAsVoted(nomination);
                } else { /* ... error handling ... */ }
                return sent;
            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Failed to send vote - " + e.getMessage());
                return false;
            }
        }
    }

    @Override
    public void resolveCastVoteRequest(Map<String, String> map) {
        synchronized (NODE_JOIN_LOCK) {
            try {
                if (map == null || !map.containsKey("VOTE")) { /* ... error handling ... */ return; }
                boolean vote = Boolean.parseBoolean(map.get("VOTE"));
                if (vote) {
                    // Use static Nomination method which now uses DBUtil
                    Nomination.addVote();
                    // ConsolePrinter already logs inside Nomination.addVote()
                } else {
                    ConsolePrinter.printInfo("[NodeJoin]: Received a 'NO' vote. Not counted towards approval.");
                }
            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Error resolving vote request - " + e.getMessage());
            }
        }
    }

    @Override
    public void endElection() {
        synchronized (NODE_JOIN_LOCK) {
            try {
                // Use static Nomination method which now uses DBUtil
                Nomination.clearNomination();
                ConsolePrinter.printInfo("[NodeJoin]: Election cleared successfully.");
            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Failed to end election - " + e.getMessage());
            }
        }
    }

    @Override
    public int getRequiredVotes() {
        // Delegate to Nomination class which reads from DB
        return dbUtil.getInt(Names.VOTES_REQUIRED);
    }

    @Override
    public int getObtainedVotes() {
        // Delegate to Nomination class which reads from DB
        return Nomination.getVotes();
    }

    @Override
    public boolean processResult() {
        synchronized (NODE_JOIN_LOCK) {
            try {
                // Use static Nomination method which now uses DBUtil
                int result = Nomination.getResult();
                switch (result) {
                    case 0: // Win
                        ConsolePrinter.printSuccess("[NodeJoin]: Nomination approved by network.");
                        if (listener != null) listener.onNodeJoinWin();
                        break;
                    case 2: // Lose
                        ConsolePrinter.printFail("[NodeJoin]: Nomination rejected by network.");
                        if (listener != null) listener.onNodeJoinLose();
                        break;
                    case -1: // In Progress
                        ConsolePrinter.printInfo("[NodeJoin]: Voting is still in progress.");
                        if (listener != null) listener.onNodeJoinProgress();
                        break;
                    case -2: // No Session
                        ConsolePrinter.printWarning("[NodeJoin]: Cannot process results, no voting session found.");
                        break;
                }
                // Clear nomination data only if voting is finished (win or lose)
                if (result == 0 || result == 2) {
                    Nomination.clearNomination();
                }

            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Error processing result - " + e.getMessage());
                e.printStackTrace();
            }
        }
        return false; // Return value seems unused, kept as original
    }

    @Override
    public Set<Nomination> getNominations() {
        // Delegate to Nomination class which reads from DB
        return new HashSet<>(Nomination.getNominations());
    }

    @Override
    public void addNomination(Nomination nomination) {
        // Delegate to Nomination class which writes to DB
        Nomination.addNomination(nomination);
    }

    // --- MessageHandler Interface Implementation ---
    // (onBroadcastMessage, onDirectMessage, onMulticastMessage remain largely the same,
    // they just parse the message and call the appropriate resolve... methods above)

    @Override
    public void onBroadcastMessage(String message) {
        // Example: Handle broadcasted nomination requests if needed,
        // though your current logic sends them directly to leaders.
    }

    @Override
    public void onDirectMessage(String message) {
        try {
            if (message == null || message.isEmpty()) return;
            Message msg = ConversionUtil.fromJson(message, Message.class);
            if (msg == null || msg.type == null) return;
            Map<String, String> payloadMap = msg.payload != null ? ConversionUtil.jsonToMap(msg.payload) : null;

            if (!allowedType.contains(msg.type)) return; // Filter unrelated messages

            switch (msg.type) {
                case NOMINATION_REQUEST -> { // Likely handled by Leaders, but good to have a case
                    ConsolePrinter.printInfo("[NodeJoin] Received nomination request from " + msg.senderIp);
                    resolveNominationRequest(msg, payloadMap);
                }
                case CAST_VOTE -> { // Received by the node that requested nomination
                    ConsolePrinter.printInfo("[NodeJoin] Received vote from " + msg.senderIp);
                    resolveCastVoteRequest(payloadMap);
                }
                default -> ConsolePrinter.printWarning("[NodeJoin] Unknown/unhandled direct message type: " + msg.type);
            }
        } catch (Exception e) {
            ConsolePrinter.printFail("[NodeJoin] Error processing direct message: " + e.getMessage());
            e.printStackTrace();
        }
    }


    @Override
    public void onMulticastMessage(String message) {
        // Multicast might be used for leaders to coordinate voting, etc.
    }

    // --- Registration and Listener ---
    public void setListener(NodeJoinListener listener) {
        this.listener = listener;
    }
    public void register(NetworkManager networkManager) {
        this.networkManager = networkManager;
        networkManager.registerHandler(this);
    }
    public void stop() {
        if (this.networkManager != null) {
            // networkManager.stop(); // Stop might be handled elsewhere
            this.networkManager.unregisterHandler(this);
        }
    }
}