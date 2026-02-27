package org.ddns.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.ddns.constants.ConfigKey;
import org.ddns.constants.ElectionType;
import org.ddns.constants.Role;
import org.ddns.db.DBUtil;
import org.ddns.net.Message;
import org.ddns.net.MessageHandler;
import org.ddns.net.MessageType;
import org.ddns.net.NetworkManager;
import org.ddns.node.NodeConfig;
import org.ddns.util.ConversionUtil;
import org.ddns.util.NetworkUtility;
import org.ddns.util.TimeUtil;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.ddns.db.*;
import java.util.*;

import static org.ddns.Main.ELECTION_PASSWORD;

/**
 * Election lifecycle manager.
 * <p>
 * Responsibilities:
 * - Creates an election and broadcasts the nomination.
 * - Receives nominations from others and persists a local "to vote" list.
 * - Casts a vote exactly once per nomination (no dup votes).
 * - Receives votes and stores them in a local vote-box (deduped).
 * - Computes result against a snapshot quorum of all known nodes.
 * <p>
 * Operational notes:
 * - Storage keys used: NOMINATIONS (Set<Nomination> JSON), VOTE_BOX (Set<Vote>
 * JSON)
 * - This version avoids schema changes and does not add signatures/election
 * IDs.
 * See "Security & correctness gaps" section after the code for recommended v2
 * upgrades.
 */
public class Election implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(Election.class);
    public static final int ELECTION_CREATED = 8;
    public static int ACCEPTED = 1;
    public static int REJECTED = 2;
    public static int WRONG_PASSWORD = 3;
    public static int INVALID_INPUT = 4;
    public static int INVALID_NODE_NAME = 5;
    public static int INVALID_DESCRIPTION = 6;
    public static int INVALID_TIME = 7;

    // ---- Construction ----

    /**
     * Registers to the network manager and initializes empty local stores if
     * needed.
     * Never throws: logs and continues with empty sets on any parsing issue.
     */
    public Election(NetworkManager networkManager) {
        Objects.requireNonNull(networkManager, "networkManager must not be null");
        networkManager.registerHandler(this);

        // Ensure we have sane initial state for nominations and votes
        if (loadNominations() == null)
            saveNomination(new HashSet<>());
        if (loadVotes() == null)
            saveVotes(new HashSet<>());
    }

    // ---- API: Create & Broadcast ----

    private static <T> Set<T> safeSet(Set<T> s) {
        return (s == null) ? new HashSet<>() : s;
    }

    // ---- API: Cast vote ----

    private static String safe(String s, int maxLen) {
        if (s == null)
            return null;
        String t = s.trim();
        if (t.length() > maxLen)
            t = t.substring(0, maxLen);
        return t;
    }

    // ---- API: Read nominations ----

    /**
     * Create a new election and broadcast this node's nomination to eligible roles.
     *
     * @param electionType  type of election (non-null)
     * @param timeInMinutes voting window (>0)
     * @param nodeName      display name (optional, sanitized)
     * @param description   reason/extra info (optional, sanitized)
     *                      <p>
     *                      Failure handling:
     *                      - Validates inputs and self-node availability.
     *                      - Initializes/clears a fresh vote-box for THIS election
     *                      instance.
     *                      - Returns immediately on broadcast failure; peers that
     *                      received it can still vote.
     */
    public static void createElection(ElectionType electionType,
            int timeInMinutes,
            String nodeName,
            String description) {

        if (electionType == null) {
            log.error("[Election] electionType must not be null");
            return;
        }
        if (timeInMinutes <= 0) {
            log.error("[Election] timeInMinutes must be > 0");
            return;
        }

        final NodeConfig selfNode = DBUtil.getInstance().getSelfNode();
        if (selfNode == null || selfNode.getPublicKey() == null) {
            log.error("[Election] Self node or its public key not configured.");
            return;
        }

        final long startTime = TimeUtil.getCurrentUnixTime();
        final long endTime = TimeUtil.getTimestampAfterMinutes(startTime, timeInMinutes);

        final Nomination nomination = new Nomination(
                selfNode,
                /* vote flag */ false,
                startTime,
                endTime,
                NetworkUtility.getLocalIpAddress(),
                electionType,
                safe(nodeName, 256),
                safe(description, 1024));

        // Fresh vote-box for *this* election window
        saveVotes(new HashSet<>());

        try {
            Message msg = new Message(
                    MessageType.CREATE_ELECTION,
                    NetworkUtility.getLocalIpAddress(),
                    DBUtil.getInstance().getPublicKey(), // sender's public key
                    ConversionUtil.toJson(nomination));

            // Broadcast to ALL nodes
            NetworkManager.broadcast(
                    ConversionUtil.toJson(msg),
                    DBUtil.getInstance().getAllNodes(),
                    Set.of(Role.ANY));

            log.info(
                    "[Election] Broadcasted CREATE_ELECTION (" + electionType + "), expires at " + endTime);
        } catch (Exception e) {
            log.error("[Election] Failed to broadcast CREATE_ELECTION: " + e.getMessage());
        }
    }

    /**
     * Cast a vote to a specific nomination, if still within its time window.
     * Dedup behavior:
     * - If we've already removed it locally (we voted earlier), it's a no-op.
     * - If voting window has expired, we remove it and warn.
     */
    public static void casteVote(Nomination nomination) {
        if (nomination == null) {
            log.warn("[Election] casteVote called with null nomination");
            return;
        }

        long now = TimeUtil.getCurrentUnixTime();
        if (TimeUtil.isExpired(now, nomination.getExpireTime())) {
            log.error("[Election] Voting window expired; cannot vote. Removing local nomination.");
            removeNomination(nomination);
            return;
        }

        NodeConfig selfNode = DBUtil.getInstance().getSelfNode();
        if (selfNode == null || selfNode.getPublicKey() == null) {
            log.error("[Election] Self node/public key missing; cannot vote.");
            return;
        }

        Vote vote = new Vote(selfNode, true);

        Message msg = new Message(
                MessageType.CASTE_VOTE,
                nomination.getIpAddress(), // receiver IP (candidate)
                nomination.getNodeConfig().getPublicKey(), // receiver pubkey
                ConversionUtil.toJson(vote));

        try {
            NetworkManager.sendDirectMessage(nomination.getIpAddress(), ConversionUtil.toJson(msg));
            // Mark as handled locally by simply removing the nomination
            removeNomination(nomination);
            log.info("[Election] Vote sent to " + nomination.getIpAddress());
        } catch (Exception e) {
            log.error("[Election] Failed to send vote: " + e.getMessage());
        }
    }

    // ---- Votes (receiver side) ----

    /**
     * Return a copy of outstanding nominations that we haven't voted on AND are not
     * expired.
     * (We treat the 'vote' flag inside Nomination as advisory; primary control is
     * store removal.)
     */
    public static Set<Nomination> getNominations() {
        Set<Nomination> nominations = safeSet(loadNominations());
        long now = TimeUtil.getCurrentUnixTime();
        nominations.removeIf(n -> n == null ||
                n.getNodeConfig() == null ||
                TimeUtil.isExpired(now, n.getExpireTime()));
        // Persist cleanup in case we filtered any expired ones
        saveNomination(nominations);
        return new HashSet<>(nominations);
    }

    /**
     * Remove a specific nomination (idempotent).
     */
    public static void removeNomination(Nomination nomination) {
        if (nomination == null)
            return;
        Set<Nomination> nominations = safeSet(loadNominations());
        nominations.remove(nomination);
        saveNomination(nominations);
    }

    // ---- MessageHandler ----

    /**
     * Append a vote to the vote-box if:
     * - vote payload is well-formed
     * - voter node has a public key
     * - voter hasn't already voted (dedup by voter public key)
     */
    public void addVote(String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("[Election] addVote: empty payload");
            return;
        }

        Vote vote = null;
        try {
            vote = ConversionUtil.fromJson(payload, Vote.class);
        } catch (Exception e) {
            log.warn("[Election] addVote: malformed Vote JSON ignored: " + e.getMessage());
            return;
        }

        if (vote == null || vote.getNodeConfig() == null || vote.getNodeConfig().getPublicKey() == null) {
            log.warn("[Election] addVote: missing voter public key, ignoring.");
            return;
        }

        Set<Vote> votes = safeSet(loadVotes());

        // Deduplicate by voter public key string (safer than full object equality)
        Vote finalVote = vote;
        boolean alreadyVoted = votes.stream().anyMatch(v -> sameVoter(v, finalVote));
        if (alreadyVoted) {
            log.info("[Election] Duplicate vote from same voter ignored.");
            return;
        }

        votes.add(vote);
        saveVotes(votes);
        log.info("[Election] Vote recorded. Total votes: " + votes.size());
    }

    /**
     * Compute the result using the current vote-box, then clear it.
     * Quorum rule: votes >= requiredVotes (all known nodes).
     *
     * @return true if quorum met; false otherwise.
     */
    public static boolean getResult() {
        int requiredVotes = getRequiredVotes();
        int votesReceived = safeSet(loadVotes()).size();
        deleteVoteBox(); // make result read-once
        log.info("[Election] Result check: received=" + votesReceived + ", required=" + requiredVotes);
        return votesReceived >= requiredVotes;
    }

    @Override
    public void onBroadcastMessage(String message) {
        // not used currently
    }

    // ---- Helpers: nominations ----

    @Override
    public void onDirectMessage(String message) {
        if (message == null || message.isBlank())
            return;

        Message envelope;
        try {
            envelope = ConversionUtil.fromJson(message, Message.class);
        } catch (Exception e) {
            log.warn("[Election] Dropped malformed direct message: " + e.getMessage());
            return;
        }

        if (envelope == null || envelope.type == null) {
            log.warn("[Election] Dropped message with null type.");
            return;
        }

        try {
            switch (envelope.type) {
                case CREATE_ELECTION -> addNomination(envelope.payload);
                case CASTE_VOTE -> addVote(envelope.payload);
                default -> {
                    /* ignore others */ }
            }
        } catch (Exception e) {
            log.error("[Election] Error processing " + envelope.type + ": " + e.getMessage());
        }
    }

    @Override
    public void onMulticastMessage(String message) {
        // not used currently
    }

    /**
     * Accepts a nomination payload and persists it if valid & within time.
     * Idempotent on Set semantics + equals/hashCode of Nomination.
     */
    public void addNomination(String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("[Election] addNomination: empty payload");
            return;
        }

        Nomination nomination;
        try {
            nomination = ConversionUtil.fromJson(payload, Nomination.class);
        } catch (Exception e) {
            log.warn("[Election] addNomination: malformed Nomination JSON ignored: " + e.getMessage());
            return;
        }

        if (!isNominationValid(nomination)) {
            log.warn("[Election] addNomination: invalid nomination ignored.");
            return;
        }

        long now = TimeUtil.getCurrentUnixTime();
        if (TimeUtil.isExpired(now, nomination.getExpireTime())) {
            log.info("[Election] addNomination: already expired; ignoring.");
            return;
        }

        Set<Nomination> nominations = safeSet(loadNominations());
        nominations.add(nomination);
        saveNomination(nominations);
        log.info("[Election] Nomination stored from " + nomination.getIpAddress());

    }

    private boolean isNominationValid(Nomination n) {
        return n != null &&
                n.getNodeConfig() != null &&
                n.getNodeConfig().getPublicKey() != null &&
                n.getIpAddress() != null &&
                n.getElectionType() != null &&
                n.getExpireTime() > 0 &&
                n.getStartTime() > 0 &&
                n.getExpireTime() > n.getStartTime();
    }

    // ---- Helpers: votes ----

    private static void saveNomination(Set<Nomination> nominationSet) {
        DBUtil.getInstance().putString(ConfigKey.NOMINATIONS.key(), ConversionUtil.toJson(safeSet(nominationSet)));
    }

    private static Set<Nomination> loadNominations() {
        String json = DBUtil.getInstance().getString(ConfigKey.NOMINATIONS.key());
        Set<Nomination> set = ConversionUtil.jsonToSet(json, Nomination.class);
        return set != null ? set : new HashSet<>();
    }

    private static Set<Vote> loadVotes() {
        String json = DBUtil.getInstance().getString(ConfigKey.VOTE_BOX.key());
        Set<Vote> set = ConversionUtil.jsonToSet(json, Vote.class);
        return set != null ? set : new HashSet<>();
    }

    private static void saveVotes(Set<Vote> votes) {
        DBUtil.getInstance().putString(ConfigKey.VOTE_BOX.key(), ConversionUtil.toJson(safeSet(votes)));
    }

    // ---- Quorum ----

    public static void deleteVoteBox() {
        DBUtil.getInstance().delete(ConfigKey.VOTE_BOX.key());
    }

    // ---- Utils ----

    private boolean sameVoter(Vote a, Vote b) {
        if (a == null || b == null)
            return false;
        if (a.getNodeConfig() == null || b.getNodeConfig() == null)
            return false;
        if (a.getNodeConfig().getPublicKey() == null || b.getNodeConfig().getPublicKey() == null)
            return false;
        try {
            String ak = org.ddns.bc.SignatureUtil.getStringFromKey(a.getNodeConfig().getPublicKey());
            String bk = org.ddns.bc.SignatureUtil.getStringFromKey(b.getNodeConfig().getPublicKey());
            return Objects.equals(ak, bk);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Required votes = ALL nodes (unanimous approval required).
     * Uses current snapshot. See analysis below for implications.
     */
    public static int getRequiredVotes() {
        // Unanimous voting - all nodes must approve
        return DBUtil.getInstance().getAllNodes().size();
    }

    public static int getResult(String password) {

        if (password == null || password.isBlank() || password.trim().isEmpty()) {
            return INVALID_INPUT;
        }

        String storedHash = DBUtil.getInstance().getString(ELECTION_PASSWORD);
        String providedHash = hashPassword(password.toCharArray());
        Arrays.fill(password.toCharArray(), '\0');

        if (storedHash == null || !storedHash.equals(providedHash))
            return WRONG_PASSWORD;

        boolean result = getResult();
        if (result)
            return ACCEPTED;
        return REJECTED;

    }

    public static int createElection(String password, String name,
            int time, String description, ElectionType electionType) {

        if (password == null || password.isEmpty()) {
            return INVALID_INPUT;
        }
        String hash = hashPassword(password.toCharArray());

        DBUtil.getInstance().putString(ELECTION_PASSWORD, hash);

        Arrays.fill(password.toCharArray(), '\0');

        TimeUtil.waitForSeconds(1);

        if (name.isEmpty() || name.length() > 128) {

            return INVALID_NODE_NAME;
        }

        if (time <= 0 || time > 60 * 24) {

            log.warn("Invalid time limit. Must be between 1 and 1440 minutes.");

            return INVALID_TIME;
        }

        if (description.isEmpty() || description.length() > 1024) {

            return INVALID_DESCRIPTION;
        }
        createElection(electionType, time, name, description);

        return ELECTION_CREATED;

    }

    public static String hashPassword(char[] password) {
        try {
            byte[] bytes = new String(password).getBytes("UTF-8");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            Arrays.fill(bytes, (byte) 0);
            return bytesToHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
