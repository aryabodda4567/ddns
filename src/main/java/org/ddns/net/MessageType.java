package org.ddns.net;

public enum MessageType {
    // Node Discovery & Joining
    DISCOVERY_REQUEST,  // A new node broadcasting to see who is on the network
    DISCOVERY_ACK,      // A leader node replying directly to a new node
    JOIN_REQUEST_TX,    // A new node broadcasting a formal transaction to join
    NOMINATION_REQUEST,          // A leader sending a "yes/no" vote directly to the new node
    CAST_VOTE,

    // Data Synchronization
    SYNC_REQUEST,       // A new node asking a leader for the latest DNS state
    SYNC_RESPONSE,      // A leader sending the DNS state directly to the new node

    // DNS Operations
    DNS_REGISTER_TX,
    DNS_UPDATE_TX,
    DNS_DELETE_TX,

    // Consensus & Governance
    BLOCK,              // A leader broadcasting a new block
    PROMOTION_REQUEST_TX,
    PROMOTION_VOTE,

    //Bootstrapping
    BOOTSTRAP_REQUEST,
    BOOTSTRAP_RESPONSE,

    //    Blockchain transactions
    TRANSACTION,
    PROMOTE_TO_LEADER,
    ADD_NODE_TO_BOOTSTRAP
}