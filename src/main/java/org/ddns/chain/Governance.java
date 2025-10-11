package org.ddns.chain;

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
 * Defensive: checks for null/missing storage values and avoids throwing
 * NumberFormatException / NPE from bad/missing storage.
 */
public class Governance {

    public static void updateNominations(Message message) {
        if (message == null || message.senderIp == null || message.senderIp.trim().isEmpty()) {
            System.err.println("updateNominations: invalid message or sender IP; skipping.");
            return;
        }


        String listJson = PersistentStorage.getString(Names.NOMINATIONS);
        List<String> list = ConversionUtil.jsonToList(listJson, String.class);
        if (list == null) {
            list = new ArrayList<>();
        }
        // Avoid duplicate nominations
        if (!list.contains(message.senderIp)) {
            list.add(message.senderIp);
            PersistentStorage.put(Names.NOMINATIONS, ConversionUtil.toJson(list));
        }
    }

    public static void updateNominations(List<String> list, String nomineeIp) {
        if (list == null) return;
        list.remove(nomineeIp);

        PersistentStorage.put(Names.NOMINATIONS, ConversionUtil.toJson(list));
    }

    public static List<String> getNominations() {

        String listJson = PersistentStorage.getString(Names.NOMINATIONS);
        List<String> list = ConversionUtil.jsonToList(listJson, String.class);
        return (list == null) ? new ArrayList<>() : list;
    }

    public static void castVote(String receiverIp, Boolean isAccepted, PublicKey publicKey) {
        if (receiverIp == null || receiverIp.trim().isEmpty()) {
            System.err.println("castVote: receiverIp is null/empty; skipping.");
            return;
        }
        if (isAccepted == null) {
            System.err.println("castVote: isAccepted is null; skipping.");
            return;
        }

        Map<String, String> map = new HashMap<>();
        map.put("VOTE", Boolean.toString(isAccepted));
        Message message = new Message(
                MessageType.JOIN_VOTE,
                NetworkUtility.getLocalIpAddress(),
                publicKey,
                ConversionUtil.toJson(map)
        );
        System.out.println("Sending vote to " + receiverIp);
        try {
            NetworkManager.sendDirectMessage(receiverIp, ConversionUtil.toJson(message));


        } catch (Exception e) {
            System.err.println("Failed to send vote to " + receiverIp + ": " + e.getMessage());
        }
    }

    public static void addVote() {


        String initTimeStr = PersistentStorage.getString(Names.VOTING_INIT_TIME);
        if (initTimeStr == null) {
            System.err.println("addVote: no voting init time present; ignoring vote.");
            return;
        }

        long initTime;
        try {
            initTime = Long.parseLong(initTimeStr);
        } catch (NumberFormatException e) {
            System.err.println("addVote: invalid init time format; ignoring vote.");
            return;
        }

        int timeLimit = PersistentStorage.getInt(Names.VOTING_TIME_LIMIT);
        if (!TimeUtil.isWithinMinutes(initTime, TimeUtil.getCurrentUnixTime(), timeLimit)) {
            System.out.println("addVote: vote outside allowed time window; ignoring.");
            return;
        }

        int current = PersistentStorage.getInt(Names.VOTE_RESULTS);
        PersistentStorage.put(Names.VOTE_RESULTS, current + 1);
    }

    public static boolean isAccepted() {

        int votes = PersistentStorage.getInt(Names.VOTE_RESULTS);
        int leaderCount = PersistentStorage.getInt(Names.TOTAL_LEADER_COUNT);
        // Use >= to tolerate slight mismatches or if leaderCount decreased
        return (leaderCount > 0) && (votes == leaderCount);
    }

    public static void createNomination(PublicKey publicKey, int timeLimitInMinutes) throws Exception {

        PersistentStorage.put(Names.VOTE_RESULTS, 0);
        PersistentStorage.put(Names.VOTING_INIT_TIME, Long.toString(TimeUtil.getCurrentUnixTime()));
        PersistentStorage.put(Names.VOTING_TIME_LIMIT, timeLimitInMinutes);
        PersistentStorage.put(Names.NOMINATIONS, ConversionUtil.toJson(new ArrayList<String>()));
        MessageHandler.createJoinRequest(publicKey);
    }

    private static void deleteNominations() {

        PersistentStorage.delete(Names.VOTE_RESULTS);
        PersistentStorage.delete(Names.VOTING_INIT_TIME);
        PersistentStorage.delete(Names.VOTING_TIME_LIMIT);
        PersistentStorage.delete(Names.NOMINATIONS);
    }

    public static void votingResults() throws Exception {


        String initTimeStr = PersistentStorage.getString(Names.VOTING_INIT_TIME);
        if (initTimeStr == null) {
            System.out.println("No voting session found.");
            return;
        }

        long initTime;
        try {
            initTime = Long.parseLong(initTimeStr);
        } catch (NumberFormatException e) {
            System.err.println("votingResults: invalid init time format; cleaning up and returning.");
            deleteNominations();
            return;
        }

        int timeLimit = PersistentStorage.getInt(Names.VOTING_TIME_LIMIT);
        int votes = PersistentStorage.getInt(Names.VOTE_RESULTS);
        int leaderCount = PersistentStorage.getInt(Names.TOTAL_LEADER_COUNT);

        System.out.println("Votes required: " + leaderCount + "\nVotes obtained: " + votes);

        if (TimeUtil.isWithinMinutes(initTime, TimeUtil.getCurrentUnixTime(), timeLimit)) {
            System.out.println("Voting is in progress. View after sometime.");
            return;
        }

        if (isAccepted()) {
            System.out.println("Join request accepted by peers.");
            MessageHandler.addLeaderRequest();
        } else {
            System.out.println("Join request rejected by peers");
        }

        deleteNominations();
    }
}
