package org.ddns.net;

public enum MessageType {
    // Boostrap Messages
    FETCH_NODES,
    ADD_NODE,
    DELETE_NODE,
    PROMOTE_NODE,
    FETCH_NODES_RESPONSE,

    CREATE_ELECTION,
    CASTE_VOTE,

    SYNC_REQUEST,
    SYNC_RESPONSE,

    ADD,
    DELETE,
    PROMOTE,

    TRANSACTION_PUBLISH,
    BLOCK_PUBLISH


}