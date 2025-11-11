package org.ddns.bc;

/**
 * Defines the types of transactions that can occur in the dDNS blockchain.
 * Each type corresponds to a specific DNS or governance operation.
 */

/**
 * Defines the types of transactions that can occur in the dDNS blockchain.
 * Each type corresponds to a specific DNS or governance operation.
 */
public enum TransactionType {
    // Domain Lifecycle
    REGISTER,
    UPDATE_RECORDS,
    TRANSFER_OWNERSHIP,
    RENEW,
    DELETE_RECORDS,
    CREATE_SUBDOMAIN,
    MANAGE_PERMISSIONS,

    // --- NEW: Governance and Node Management ---
    NODE_JOIN_REQUEST,      // A new node broadcasts a request to join the network.
    NODE_JOIN_VOTE,         // A leader node casts a vote on a join request.
    LEADER_PROMOTION_REQUEST, // A normal node requests to be promoted to a leader.
    LEADER_PROMOTION_VOTE,    // A leader node casts a vote on a promotion request.

    // System Level
    REVOKE,
    STATE_SNAPSHOT
}