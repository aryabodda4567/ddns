package org.ddns.net;

public enum MessageType {
    // Node Discovery & Joining
    DISCOVERY_REQUEST,  // A new node broadcasting to see who is on the network
    DISCOVERY_ACK,      // A leader node replying directly to a new node
    JOIN_REQUEST_TX,    // A new node broadcasting a formal transaction to join
    JOIN_VOTE,          // A leader sending a "yes/no" vote directly to the new node

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
    PROMOTION_VOTE
}