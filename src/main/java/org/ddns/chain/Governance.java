package org.ddns.chain;

import org.ddns.bc.SignatureUtil;
import org.ddns.net.Message;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.PersistentStorage;
import org.ddns.util.TimeUtil;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles voting, nominations, and governance rules in the network.
 */
public class Governance {

    public static void updateNominations(Message message) {
        PersistentStorage storage = new PersistentStorage();

        String listJson = storage.getString(Names.NOMINATIONS);
        List<String> list = ConversionUtil.jsonToList(listJson, String.class);
        if (list == null) {
            list = new ArrayList<String>();
        }
        // Avoid duplicate nominations
        if (!list.contains(message.senderIp)) {
            list.add(message.senderIp);
            storage.put(Names.NOMINATIONS, ConversionUtil.toJson(list));

            storage.put(Names.NOMINATIONS, ConversionUtil.toJson(list));
        }

//        try{
//            castVote(message.senderIp,true,SignatureUtil.getPublicKeyFromString(message.senderPublicKey));
//        } catch (Exception e) {
//                throw new RuntimeException(e);
//        }


    }

    public static void castVote(String receiverIp, Boolean isAccepted, PublicKey publicKey) {

        Map<String, String> map = new HashMap<>();
        map.put("VOTE", isAccepted + "");
        Message message = new Message(
                MessageType.JOIN_VOTE,
                NetworkUtility.getLocalIpAddress(),
                publicKey,
                ConversionUtil.toJson(map)
        );
        NetworkManager.sendDirectMessage(receiverIp, ConversionUtil.toJson(message));
    }

    public static void addVote() {
        PersistentStorage storage = new PersistentStorage();
        long initTime = Long.parseLong(storage.getString(Names.VOTING_INIT_TIME));
        int timeLimit = storage.getInt(Names.VOTING_TIME_LIMIT);

        if (!TimeUtil.isWithinMinutes(initTime, TimeUtil.getCurrentUnixTime(), timeLimit)) {
            return;
        }
        storage.put(Names.VOTE_RESULTS, storage.getInt(Names.VOTE_RESULTS) + 1);
    }

    public static boolean isAccepted() {
        PersistentStorage storage = new PersistentStorage();
        int votes = storage.getInt(Names.VOTE_RESULTS);
        int leaderCount = storage.getInt(Names.TOTAL_LEADER_COUNT);
        return votes == leaderCount;
    }

    public static void createNomination(PublicKey publicKey, int timeLimitInMinutes) throws Exception {
        PersistentStorage storage = new PersistentStorage();
        storage.put(Names.VOTE_RESULTS, 0);
        storage.put(Names.VOTING_INIT_TIME, TimeUtil.getCurrentUnixTime() + "");
        storage.put(Names.VOTING_TIME_LIMIT, timeLimitInMinutes);

        MessageHandler.createJoinRequest(publicKey);
    }

    private static void deleteNominations(){
        PersistentStorage storage = new PersistentStorage();
        storage.delete(Names.VOTE_RESULTS);
        storage.delete(Names.VOTING_INIT_TIME);
        storage.delete(Names.VOTING_TIME_LIMIT);
    }

    public static void votingResults() {
        PersistentStorage storage = new PersistentStorage();
        long initTime = Long.parseLong(storage.getString(Names.VOTING_INIT_TIME));
        int timeLimit = storage.getInt(Names.VOTING_TIME_LIMIT);

        int votes = storage.getInt(Names.VOTE_RESULTS);
        int leaderCount = storage.getInt(Names.TOTAL_LEADER_COUNT);
        System.out.println("Votes required: "+ leaderCount+"\nVotes obtained: "+votes);

        if (TimeUtil.isWithinMinutes(initTime, TimeUtil.getCurrentUnixTime(), timeLimit)) {
            System.out.println("Voting is in progress. View after sometime.");
            return;
        }

        if (isAccepted()) {
            System.out.println("Join request accepted by peers.");
            deleteNominations();
        }else{
            System.out.println("Join request rejected by peers");
        }
    }
}
