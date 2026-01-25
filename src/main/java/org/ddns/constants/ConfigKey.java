package org.ddns.constants;

public enum ConfigKey {

    TOTAL_NODE_COUNT,
    TOTAL_LEADER_COUNT,
    NOMINATIONS,
    VOTES,
    VOTES_REQUIRED,
    VOTING_INIT_TIME,
    VOTING_TIME_LIMIT,
    AVAILABLE_NODES,
    ROLE,
    BOOTSTRAP_NODE_IP,
    PUBLIC_KEY,
    PRIVATE_KEY,
    DB_FILE_NAME,
    SYNC_FULL,
    SYNC_FROM_TIME,
    DB_FILE,
    SELF_NODE,

//    Election

    ELECTION,
    VOTE_BOX;

    public String key() {
        return this.name();
    }

    @Override
    public String toString() {
        return name();
    }
}
