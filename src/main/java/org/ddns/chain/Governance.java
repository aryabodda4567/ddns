package org.ddns.chain;

import org.ddns.net.Message;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.util.ConsolePrinter; // Import the printer utility
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
        if (message == null || message.senderIp == null || message.senderIp.trim().isEmpty()) {
            ConsolePrinter.printFail("updateNominations: Invalid message or sender IP. Skipping nomination.");
            return;
        }

        String listJson = PersistentStorage.getString(Names.NOMINATIONS);
        List<String> list = ConversionUtil.jsonToList(listJson, String.class);
        if (list == null) {
            list = new ArrayList<>();
        }

        if (!list.contains(message.senderIp)) {
            list.add(message.senderIp);
            PersistentStorage.put(Names.NOMINATIONS, ConversionUtil.toJson(list));
            ConsolePrinter.printInfo("New join nomination received from: " + message.senderIp);
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
            ConsolePrinter.printFail("castVote: Receiver IP is invalid. Cannot send vote.");
            return;
        }
        if (isAccepted == null) {
            ConsolePrinter.printFail("castVote: Vote status (accepted/rejected) is null. Cannot send vote.");
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
        ConsolePrinter.printInfo("--> Sending vote to " + receiverIp + "...");
        try {
            NetworkManager.sendDirectMessage(receiverIp, ConversionUtil.toJson(message));
            ConsolePrinter.printSuccess("✓ Vote sent successfully.");
        } catch (Exception e) {
            ConsolePrinter.printFail("✗ Failed to send vote to " + receiverIp + ": " + e.getMessage());
        }
    }

    public static void addVote() {
        String initTimeStr = PersistentStorage.getString(Names.VOTING_INIT_TIME);
        if (initTimeStr == null) {
            ConsolePrinter.printWarning("No active voting session found. Ignoring received vote.");
            return;
        }

        long initTime;
        try {
            initTime = Long.parseLong(initTimeStr);
        } catch (NumberFormatException e) {
            ConsolePrinter.printFail("Corrupt voting data (init time). Ignoring vote.");
            return;
        }

        int timeLimit = PersistentStorage.getInt(Names.VOTING_TIME_LIMIT);
        if (!TimeUtil.isWithinMinutes(initTime, TimeUtil.getCurrentUnixTime(), timeLimit)) {
            ConsolePrinter.printWarning("Vote received outside the allowed time window. Ignoring.");
            return;
        }

        int current = PersistentStorage.getInt(Names.VOTE_RESULTS);
        PersistentStorage.put(Names.VOTE_RESULTS, current + 1);
        ConsolePrinter.printSuccess("✓ Vote successfully counted.");
    }

    private static boolean isAccepted() {
        int votes = PersistentStorage.getInt(Names.VOTE_RESULTS);
        int leaderCount = PersistentStorage.getInt(Names.TOTAL_LEADER_COUNT);
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
        ConsolePrinter.printInfo("Voting session data has been cleared.");
    }

    public static void votingResults() throws Exception {
        String initTimeStr = PersistentStorage.getString(Names.VOTING_INIT_TIME);
        if (initTimeStr == null) {
            ConsolePrinter.printWarning("No voting session found.");
            return;
        }

        long initTime;
        try {
            initTime = Long.parseLong(initTimeStr);
        } catch (NumberFormatException e) {
            ConsolePrinter.printFail("Corrupt voting session data found. Cleaning up...");
            deleteNominations();
            return;
        }

        int timeLimit = PersistentStorage.getInt(Names.VOTING_TIME_LIMIT);
        int votes = PersistentStorage.getInt(Names.VOTE_RESULTS);
        int leaderCount = PersistentStorage.getInt(Names.TOTAL_LEADER_COUNT);

        ConsolePrinter.printInfo("--- Voting Results ---");
        ConsolePrinter.printInfo("  Votes Required: " + leaderCount);
        ConsolePrinter.printInfo("  Votes Received: " + votes);

        if (TimeUtil.isWithinMinutes(initTime, TimeUtil.getCurrentUnixTime(), timeLimit)) {
            ConsolePrinter.printWarning("Voting is still in progress. Please check back later.");
            return;
        }

        if (isAccepted()) {
            ConsolePrinter.printSuccess("✓ Congratulations! Join request ACCEPTED by the network.");
            PersistentStorage.put(Names.ROLE, Role.NORMAL_NODE);
            MessageHandler.addNodeRequest(Role.NORMAL_NODE);
        } else {
            ConsolePrinter.printFail("✗ Join request REJECTED by the network (did not meet 100% threshold).");
        }

        deleteNominations();
    }
}