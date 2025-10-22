package org.ddns.chain.governance;

import org.ddns.chain.Names;
import org.ddns.chain.Role;
import org.ddns.net.Bootstrap;
import org.ddns.net.NodeConfig;
import org.ddns.util.ConsolePrinter;
import org.ddns.util.TimeUtil;
import java.security.PublicKey;
import java.util.*;
import org.ddns.db.DBUtil; // Import DBUtil
import java.util.*;

/**
 * Represents a nomination request and manages voting state using DBUtil.
 * Thread-safe for multithreaded environments.
 */
public class Nomination {

    // --- Instance Fields (as before) ---
    private int nominationType;
    private String ipAddress;
    private PublicKey publicKey;
    private boolean isVoted;

    // Nomination types
    public static final int JOIN = 0;
    public static final int PROMOTION = 1;

    // Lock object remains useful for coordinating complex state changes if needed,
    // although DBUtil methods themselves might be synchronized or rely on DB locks.
    private static final Object NOMINATION_LOCK = new Object();

    // --- DBUtil Instance ---
    // Get the singleton instance to interact with the database
    private static final DBUtil dbUtil = DBUtil.getInstance();

    // Constructor (no changes needed)
    public Nomination(int nominationType, String ipAddress, PublicKey publicKey) {
        this.nominationType = nominationType;
        this.ipAddress = ipAddress;
        this.publicKey = publicKey;
        this.isVoted = false;
    }

    // --- Getters & Setters (no changes needed) ---
    // ... getNominationType, setNominationType, getIpAddress, etc. ...
    public int getNominationType() { return nominationType; }
    public void setNominationType(int nominationType) { this.nominationType = nominationType; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public PublicKey getPublicKey() { return publicKey; }
    public void setPublicKey(PublicKey publicKey) { this.publicKey = publicKey; }
    public boolean isVoted() { return isVoted; }
    public void setVoted(boolean voted) { isVoted = voted; }


    @Override
    public String toString() {
        // ... (no changes needed) ...
        return "Nomination{" + // ... implementation ...
                '}';
    }

    // --- Static Methods Refactored for DBUtil ---

    /** Add a nomination to the database safely */
    public static void addNomination(Nomination nomination) {
        if (nomination == null) {
            ConsolePrinter.printWarning("[Nomination]: Cannot add null nomination.");
            return;
        }
        synchronized (NOMINATION_LOCK) { // Still useful if complex logic spans multiple DB calls
            try {
                // Read current list, add new one, save back
                List<Nomination> currentNominations = dbUtil.getNominations();
                // Avoid duplicates based on IP and type (add equals/hashCode if needed)
                if (!currentNominations.contains(nomination)) {
                    currentNominations.add(nomination);
                    dbUtil.saveNominations(currentNominations); // DBUtil handles the save
                    ConsolePrinter.printSuccess("[Nomination]: Added nomination for " + nomination.getIpAddress());
                } else {
                    ConsolePrinter.printInfo("[Nomination]: Nomination for " + nomination.getIpAddress() + " already exists.");
                }
            } catch (Exception e) {
                ConsolePrinter.printFail("[Nomination]: Failed to add nomination - " + e.getMessage());
            }
        }
    }

    /** Retrieve all nominations from the database safely */
    public static List<Nomination> getNominations() {
        // DBUtil handles thread safety for reads
        try {
            return dbUtil.getNominations();
        } catch (Exception e) {
            ConsolePrinter.printFail("[Nomination]: Failed to fetch nominations - " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Initialize a new nomination voting session using DBUtil */
    public static void createNomination(int nominationType, int timeInMinutes) {
        synchronized (NOMINATION_LOCK) {
            dbUtil.putInt(Names.VOTES, 0); // Reset vote count

            int count = 0;
            // Assuming Bootstrap uses DBUtil now
            for (NodeConfig node : Bootstrap.getInstance().getNodes()) {
                if (node.getRole() != Role.NORMAL_NODE) count++;
            }
            dbUtil.putInt(Names.VOTES_REQUIRED, count); // Store required votes

            dbUtil.putLong(Names.VOTING_INIT_TIME, TimeUtil.getCurrentUnixTime()); // Use Long for time
            dbUtil.putInt(Names.VOTING_TIME_LIMIT, timeInMinutes);
            dbUtil.saveNominations(new ArrayList<>()); // Clear existing nominations in DB
        }
        ConsolePrinter.printInfo("[Nomination]: New voting session created. Required Votes: " + dbUtil.getInt(Names.VOTES_REQUIRED));
    }

    /** Add a vote using DBUtil if within the voting time limit */
    public static void addVote() {
        synchronized (NOMINATION_LOCK) {
            long votingStart = dbUtil.getLong(Names.VOTING_INIT_TIME, 0L); // Default to 0 if not found
            int timeLimit = dbUtil.getInt(Names.VOTING_TIME_LIMIT, 0); // Default to 0

            if (votingStart == 0L || timeLimit == 0) {
                ConsolePrinter.printWarning("[Nomination]: No active voting session found. Ignoring vote.");
                return;
            }

            if (!TimeUtil.isWithinMinutes(votingStart, TimeUtil.getCurrentUnixTime(), timeLimit)) { // Use correct method
                ConsolePrinter.printWarning("[Nomination]: Vote received outside allowed time window. Ignoring.");
                return;
            }

            int votes = dbUtil.getInt(Names.VOTES);
            dbUtil.putInt(Names.VOTES, votes + 1);
            ConsolePrinter.printSuccess("[Nomination]: Vote counted successfully. Total votes: " + (votes + 1));
        }
    }

    /** Get the current vote count from DBUtil */
    public static int getVotes() {
        // Logic `(votes / 2) - 1` seems incorrect for total votes, maybe intended for quorum?
        // Returning the raw count stored. Adjust if needed.
        return dbUtil.getInt(Names.VOTES);
    }

    /** Clear voting session data using DBUtil */
    public static void clearNomination() {
        synchronized (NOMINATION_LOCK) {
            dbUtil.clearNominations(); // DBUtil handles clearing table and config keys
            ConsolePrinter.printInfo("[Nomination]: Voting session data cleared from database.");
        }
    }

    /** Determine voting result using DBUtil */
    public static int getResult() {
        synchronized (NOMINATION_LOCK) {
            long startTime = dbUtil.getLong(Names.VOTING_INIT_TIME, 0L);
            int limit = dbUtil.getInt(Names.VOTING_TIME_LIMIT, 0);

            if (startTime == 0L || limit == 0) {
                ConsolePrinter.printWarning("[Nomination]: No voting session data found.");
                return -2; // Indicate no session found
            }

            // Use TimeUtil.isWithinMinutes consistently
            if (TimeUtil.isWithinMinutes(startTime, TimeUtil.getCurrentUnixTime(), limit)) {
                // Voting still in progress
                return -1;
            }

            // Voting has ended, determine result
            int votesRequired = dbUtil.getInt(Names.VOTES_REQUIRED);
            int votesObtained = getVotes(); // Use our getter

            // Your original logic used == leaderCount which might be too strict?
            // Using >= votesRequired is safer if leader count changes mid-vote.
            // Adjust this condition based on your exact 100% or 51% rule for JOIN vs PROMOTION.
            // Assuming 100% for JOIN for now based on your description:
            boolean accepted = (votesRequired > 0) && (votesObtained >= votesRequired); // Check votesRequired > 0

            return accepted ? 0 : 2; // 0 = win, 2 = lose
        }
    }

    /** Marks a nomination as voted in the database. */
    public static void markAsVoted(Nomination nomination) {
        if (nomination == null) return;
        synchronized (NOMINATION_LOCK) {
            List<Nomination> nominationList = dbUtil.getNominations();
            boolean updated = false;
            for (Nomination n : nominationList) {
                // Ensure equals method compares relevant fields (IP, Type)
                if (n.equals(nomination) && !n.isVoted()) {
                    n.setVoted(true);
                    updated = true;
                    break;
                }
            }
            if (updated) {
                dbUtil.saveNominations(nominationList);
                ConsolePrinter.printInfo("[Nomination]: Marked as voted: " + nomination.getIpAddress());
            } else {
                ConsolePrinter.printWarning("[Nomination]: Nomination not found or already voted: " + nomination.getIpAddress());
            }
        }
    }

    // --- equals() and hashCode() ---
    // Ensure these correctly compare nominations for duplicate checks and updates
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Nomination that = (Nomination) o;
        // Compare by IP and Type primarily
        return nominationType == that.nominationType && Objects.equals(ipAddress, that.ipAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nominationType, ipAddress);
    }
}