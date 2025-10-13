package org.ddns.chain.governance;

import org.ddns.chain.Role;
import org.ddns.net.*;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.PersistentStorage;

import java.security.PublicKey;
import java.util.*;

/**
 * Handles node joining governance logic.
 * Thread-safe and robust for concurrent usage.
 */
public class NodeJoin implements Voting,MessageHandler {

    private static final Object NODE_JOIN_LOCK = new Object();
    private static  final Set<MessageType> allowedType;
    private NetworkManager networkManager ;
    static {
        allowedType = Set.of(MessageType.NOMINATION_REQUEST,
                MessageType.CAST_VOTE);
    }


    private NodeJoinListener listener;

    public interface NodeJoinListener {
        void onBootstrapNodesReceived(Set<NodeConfig> nodes);
    }
    /**
     * Creates a nomination request for joining the network.
     *
     * @param timeLimit Voting time limit in minutes.
     * @return true if the request was broadcast successfully, false otherwise.
     */
    @Override
    public boolean createNominationRequest(int timeLimit) {
        synchronized (NODE_JOIN_LOCK) {
            try {
                String selfIp = NetworkUtility.getLocalIpAddress();
                PublicKey publicKey = PersistentStorage.getPublicKey();

                if (selfIp == null || publicKey == null) {
                    ConsolePrinter.printFail("[NodeJoin]: Missing IP or Public Key.");
                    return false;
                }

                // Create nomination
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

                boolean broadcastSuccess = NetworkManager.broadcast(
                        ConversionUtil.toJson(message),
                        new Bootstrap().getNodes(),
                        roles
                );

                if (broadcastSuccess) {
                    Nomination.createNomination(Nomination.JOIN, timeLimit);
                    ConsolePrinter.printSuccess("[NodeJoin]: Nomination request broadcasted successfully.");
                    return true;
                } else {
                    ConsolePrinter.printWarning("[NodeJoin]: Failed to broadcast nomination request.");
                    return false;
                }

            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Exception during createNominationRequest - " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Handles an incoming nomination request message.
     */
    @Override
    public void resolveNominationRequest(Message message, Map<String, String> payload) {
        synchronized (NODE_JOIN_LOCK) {
            try {
                if (payload == null || !payload.containsKey("NOMINATION")) {
                    ConsolePrinter.printWarning("[NodeJoin]: Received invalid nomination payload.");
                    return;
                }

                Nomination nomination = ConversionUtil.fromJson(payload.get("NOMINATION"), Nomination.class);
                if (nomination == null) {
                    ConsolePrinter.printWarning("[NodeJoin]: Failed to parse nomination from payload.");
                    return;
                }

                Nomination.addNomination(nomination);
                ConsolePrinter.printInfo("[NodeJoin]: Nomination added successfully for " + nomination.getIpAddress());

            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Error resolving nomination request - " + e.getMessage());
            }
        }
    }

    /**
     * Creates a vote request for a given nomination.
     */
    @Override
    public boolean createCastVoteRequest(boolean response, Nomination nomination) {
        synchronized (NODE_JOIN_LOCK) {
            if (nomination == null) {
                ConsolePrinter.printWarning("[NodeJoin]: Cannot cast vote for null nomination.");
                return false;
            }

            if (nomination.isVoted()){
                ConsolePrinter.printInfo("[NodeJoin]: Vote already casted");
            }

            try {
                Map<String, String> map = new HashMap<>();
                map.put("VOTE", Boolean.toString(response));

                Message message = new Message(
                        MessageType.CAST_VOTE,
                        NetworkUtility.getLocalIpAddress(),
                        PersistentStorage.getPublicKey(),
                        ConversionUtil.toJson(map)
                );

                boolean sent = NetworkManager.sendDirectMessage(
                        nomination.getIpAddress(),
                        ConversionUtil.toJson(message)
                );

                if (sent) {
                    ConsolePrinter.printSuccess("[NodeJoin]: Vote sent to " + nomination.getIpAddress());
                    Nomination.markAsVoted(nomination);
                } else {
                    ConsolePrinter.printWarning("[NodeJoin]: Failed to send vote to " + nomination.getIpAddress());
                }
                return sent;

            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Failed to send vote - " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Processes an incoming cast vote message.
     */
    @Override
    public void resolveCastVoteRequest(Map<String, String> map) {

        synchronized (NODE_JOIN_LOCK) {
            try {
                if (map == null || !map.containsKey("VOTE")) {
                    ConsolePrinter.printWarning("[NodeJoin]: Received malformed vote request.");
                    return;
                }

                boolean vote = Boolean.parseBoolean(map.get("VOTE"));
                if (vote) {

                    Nomination.addVote();
                    ConsolePrinter.printSuccess("[NodeJoin]: Vote counted successfully.");
                } else {
                    ConsolePrinter.printInfo("[NodeJoin]: Vote rejected.");
                }

            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Error resolving vote request - " + e.getMessage());
            }
        }
    }

    /** Ends the current election */
    @Override
    public void endElection() {
        synchronized (NODE_JOIN_LOCK) {
            try {
                Nomination.clearNomination();
                ConsolePrinter.printInfo("[NodeJoin]: Election cleared successfully.");
            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Failed to end election - " + e.getMessage());
            }
        }
    }

    /** Returns total required votes */
    @Override
    public int getRequiredVotes() {
        try {
            int count = 0;
            for (NodeConfig node : new Bootstrap().getNodes()) {
                if (!node.getRole().equals(Role.NORMAL_NODE)) count++;
            }
            return count;
        } catch (Exception e) {
            ConsolePrinter.printFail("[NodeJoin]: Failed to get required votes - " + e.getMessage());
            return 0;
        }
    }

    /** Returns total obtained votes */
    @Override
    public int getObtainedVotes() {
        try {
            return Nomination.getVotes();
        } catch (Exception e) {
            ConsolePrinter.printFail("[NodeJoin]: Failed to get obtained votes - " + e.getMessage());
            return 0;
        }
    }

    /**
     * Process and display the election result
     *
     * @return
     */
    @Override
    public boolean processResult() {
        synchronized (NODE_JOIN_LOCK) {
            try {
                int result = Nomination.getResult();
                switch (result) {
                    case 0 -> ConsolePrinter.printSuccess("[NodeJoin]: Nomination approved (All votes received).");
                    case 2 -> ConsolePrinter.printFail("[NodeJoin]: Nomination rejected (Insufficient votes).");
                    case -1 -> ConsolePrinter.printInfo("[NodeJoin]: Voting is still in progress.");
                }
            } catch (Exception e) {
                ConsolePrinter.printFail("[NodeJoin]: Error processing result - " + e.getMessage());
            }
        }
        return false;
    }

    /** Returns all active nominations */
    @Override
    public Set<Nomination> getNominations() {
        try {
            return new HashSet<>(Nomination.getNominations());
        } catch (Exception e) {
            ConsolePrinter.printFail("[NodeJoin]: Failed to fetch nominations - " + e.getMessage());
            return Collections.emptySet();
        }
    }

    /** Add a new nomination */
    @Override
    public void addNomination(Nomination nomination) {
        if (nomination == null) {
            ConsolePrinter.printWarning("[NodeJoin]: Cannot add null nomination.");
            return;
        }

        try {
            Nomination.addNomination(nomination);
        } catch (Exception e) {
            ConsolePrinter.printFail("[NodeJoin]: Failed to add nomination - " + e.getMessage());
        }
    }

    @Override
    public void onBroadcastMessage(String message) {

    }

    @Override
    public void onDirectMessage(String message) {
        try {
            if (message == null || message.isEmpty()) return;

            // Deserialize the message object
            Message msg = ConversionUtil.fromJson(message, Message.class);
            if (msg == null || msg.type == null) return;

            // Deserialize the payload into a map
            Map<String, String> payloadMap = msg.payload != null
                    ? ConversionUtil.jsonToMap(msg.payload)
                    : null;

            if(!allowedType.contains(msg.type)) return;
            switch (msg.type) {

                // --------------------------------------------------------
                case NOMINATION_REQUEST -> {
                    // A new nomination request has been received
                    ConsolePrinter.printInfo("[NodeJoin] Received nomination request from " + msg.senderIp);

                    if (payloadMap != null && payloadMap.containsKey("NOMINATION")) {
                        // Add nomination to local store
                        resolveNominationRequest(msg, payloadMap);

                    } else {
                        ConsolePrinter.printWarning("[NodeJoin] Invalid nomination payload from " + msg.senderIp);
                    }
                }

                // --------------------------------------------------------
                case CAST_VOTE -> {
                    // Received a vote for a nomination this node initiated
                    ConsolePrinter.printInfo("[NodeJoin] Received vote from " + msg.senderIp);

                    if (payloadMap != null && payloadMap.containsKey("VOTE")) {
                        resolveCastVoteRequest(payloadMap);
                    } else {
                        ConsolePrinter.printWarning("[NodeJoin] Invalid vote payload from " + msg.senderIp);
                    }
                }

                // --------------------------------------------------------
                default -> ConsolePrinter.printWarning("[NodeJoin] Unknown direct message type: " + msg.type);
            }

        } catch (Exception e) {
            ConsolePrinter.printFail("[NodeJoin] Error processing direct message: " + e.getMessage());
        }
    }


    @Override
    public void onMulticastMessage(String message) {

    }
    public void setListener(NodeJoinListener listener) {
        this.listener = listener;
    }
    public void register(NetworkManager networkManager){
        this.networkManager = networkManager;
        networkManager.registerHandler(this);

    }

    public void stop(){
        this.networkManager.stop();
        this.networkManager.unregisterHandler(this);
    }

}
