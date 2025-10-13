package org.ddns.chain.governance;

import org.ddns.chain.Names;
import org.ddns.chain.Role;
import org.ddns.net.Bootstrap;
import org.ddns.net.NodeConfig;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.ConversionUtil;
import org.ddns.util.PersistentStorage;
import org.ddns.util.TimeUtil;

import java.security.PublicKey;
import java.util.*;

/**
 * Represents a nomination request in the network governance process.
 * Thread-safe for multithreaded environments.
 */
public class Nomination {

    // Nomination types
    public static final int JOIN = 0;
    public static final int PROMOTION = 1;

    private int nominationType;
    private String ipAddress;
    private PublicKey publicKey;
    private boolean isVoted;

    // Lock object for atomic operations
    private static final Object NOMINATION_LOCK = new Object();

    // Constructor
    public Nomination(int nominationType, String ipAddress, PublicKey publicKey) {
        this.nominationType = nominationType;
        this.ipAddress = ipAddress;
        this.publicKey = publicKey;
        this.isVoted = false;
    }



    // Getters & Setters
    public int getNominationType() { return nominationType; }
    public void setNominationType(int nominationType) { this.nominationType = nominationType; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public PublicKey getPublicKey() { return publicKey; }
    public void setPublicKey(PublicKey publicKey) { this.publicKey = publicKey; }

    public boolean isVoted() {
        return isVoted;
    }

    public void setVoted(boolean voted) {
        isVoted = voted;
    }

    @Override
    public String toString() {
        return "Nomination{" +
                "nominationType=" + (nominationType == JOIN ? "JOIN" : "PROMOTION") +
                ", ipAddress='" + ipAddress + '\'' +
                ", publicKey=" + (publicKey != null ? publicKey.hashCode() : "null") +
                ", is voted="+ isVoted+
                '}';
    }

    // --------------------------------------------
    // THREAD-SAFE NOMINATION STORAGE
    // --------------------------------------------

    /** Add a nomination safely */
    public static void addNomination(Nomination nomination) {
        if (nomination == null) {
            ConsolePrinter.printWarning("[Nomination]: Cannot add null nomination.");
            return;
        }

        synchronized (NOMINATION_LOCK) {
            try {
                String json = PersistentStorage.getString(Names.NOMINATIONS);
                List<Nomination> list = (json == null || json.isEmpty())
                        ? new ArrayList<>()
                        : ConversionUtil.jsonToList(json, Nomination.class);

                list.add(nomination);
                PersistentStorage.put(Names.NOMINATIONS, ConversionUtil.toJson(list));
                ConsolePrinter.printSuccess("[Nomination]: Added nomination for " + nomination.getIpAddress());
            } catch (Exception e) {
                ConsolePrinter.printFail("[Nomination]: Failed to add nomination - " + e.getMessage());
            }
        }
    }

    /** Retrieve all nominations safely */
    public static List<Nomination> getNominations() {
        synchronized (NOMINATION_LOCK) {
            try {
                String json = PersistentStorage.getString(Names.NOMINATIONS);
                if (json == null || json.isEmpty()) return Collections.emptyList();

                List<Nomination> list = ConversionUtil.jsonToList(json, Nomination.class);
                return Collections.unmodifiableList(list);
            } catch (Exception e) {
                ConsolePrinter.printFail("[Nomination]: Failed to fetch nominations - " + e.getMessage());
                return Collections.emptyList();
            }
        }
    }

    // --------------------------------------------
    // VOTING METHODS
    // --------------------------------------------

    /** Initialize a new nomination voting session */
    public static void createNomination(int nominationType, int timeInMinutes) {
        synchronized (NOMINATION_LOCK) {
         //   PersistentStorage.put(Names.NOMINATIONS, ConversionUtil.toJson(new ArrayList<>()));
            PersistentStorage.put(Names.VOTES, 0);

            int count = 0;
            for (NodeConfig node : new Bootstrap().getNodes()) {
                if (!node.getRole().equals(Role.NORMAL_NODE)) count++;
            }
            PersistentStorage.put(Names.VOTES_REQUIRED, count);

            PersistentStorage.put(Names.VOTING_INIT_TIME, TimeUtil.getCurrentUnixTime());
            PersistentStorage.put(Names.VOTING_TIME_LIMIT, timeInMinutes);
        }
    }

    /** Add a vote if within the voting time limit */
    public static void addVote() {
        synchronized (NOMINATION_LOCK) {

            long votingStart = PersistentStorage.getInt(Names.VOTING_INIT_TIME);
            int timeLimit = PersistentStorage.getInt(Names.VOTING_TIME_LIMIT);

            if (!TimeUtil.isWithinMinutesFromNow(votingStart, timeLimit)) return;

            int votes = PersistentStorage.getInt(Names.VOTES);

            PersistentStorage.put(Names.VOTES, votes + 1);

        }
    }

    /** Get the current vote count */
    public static int getVotes() {
        return Math.max((PersistentStorage.getInt(Names.VOTES) / 2) - 1, 0);
    }

    /** Clear votes (after voting session ends) */
    public static void clearNomination() {
        synchronized (NOMINATION_LOCK) {
            PersistentStorage.delete(Names.VOTES);
            PersistentStorage.delete(Names.VOTES_REQUIRED);
            PersistentStorage.delete(Names.VOTING_INIT_TIME);
            PersistentStorage.delete(Names.VOTING_TIME_LIMIT);
        }
    }

    /**
     * Determine voting result
     * @return 0 = win, 2 = lose, -1 = voting not completed
     */
    public static int getResult() {
        synchronized (NOMINATION_LOCK) {
            long startTime = PersistentStorage.getInt(Names.VOTING_INIT_TIME);
            int limit = PersistentStorage.getInt(Names.VOTING_TIME_LIMIT);

            if (TimeUtil.isWithinMinutesFromNow(startTime, limit)) {
                ConsolePrinter.printInfo("Voting is not completed yet.");
                return -1;
            }

            int votesRequired = 0;
            for (NodeConfig node : new Bootstrap().getNodes()) {
                if (!node.getRole().equals(Role.NORMAL_NODE)) votesRequired++;
            }

            return votesRequired == getVotes() ? 0 : 2;
        }
    }


    /**
     * Marks a nomination as voted in the persistent storage.
     * <p>
     * This method is thread-safe. It searches for the nomination in the stored list
     * (using equals/hashCode), sets its `voted` flag to true, and updates the storage.
     * If the nomination does not exist, it does nothing.
     * </p>
     *
     * @param nomination The Nomination object to mark as voted.
     */
    public static void markAsVoted(Nomination nomination) {
        if (nomination == null) return;

        synchronized (NOMINATION_LOCK) {
            List<Nomination> nominationList = getNominations();
            boolean updated = false;

            for (Nomination n : nominationList) {
                if (n.getIpAddress().equals(nomination.getIpAddress())
                        && !n.isVoted() && n.getNominationType() == nomination.getNominationType()) {
                    n.setVoted(true);
                    updated = true;
                    break;
                }
            }

            if (updated) {
                PersistentStorage.put(Names.NOMINATIONS, ConversionUtil.toJson(nominationList));
                ConsolePrinter.printInfo("[Nomination]: Marked as voted: " + nomination.getIpAddress());
            } else {
                ConsolePrinter.printWarning("[Nomination]: Nomination not found: " + nomination.getIpAddress());
            }
        }
    }



    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Nomination that = (Nomination) o;
        return getNominationType() == that.getNominationType() && Objects.equals(getIpAddress(), that.getIpAddress());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getNominationType(), getIpAddress());
    }
}
