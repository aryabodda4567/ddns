package org.ddns.chain;

import org.ddns.net.*;
import org.ddns.util.ConsolePrinter;

import org.ddns.db.DBUtil; // Import DBUtil
import org.ddns.net.*;
// Removed: import org.ddns.util.PersistentStorage;

/**
 * Initializes the node's role and triggers corresponding network actions.
 * Uses DBUtil for persistence.
 */
public class NodeInitializer {
    private final Bootstrap bootstrap;
    // --- Add DBUtil instance ---
    private final DBUtil dbUtil;

    public NodeInitializer() {
        this.bootstrap = Bootstrap.getInstance(); // Assumes Bootstrap uses DBUtil internally
        this.dbUtil = DBUtil.getInstance();     // Get the DBUtil instance
    }

    /**
     * Initializes the node as the Genesis node.
     * Sets the role in the database and requests to be added to the (initially empty) bootstrap list.
     */
    public void initGenesisNode() {
        ConsolePrinter.printInfo("[NodeInitializer] Initializing as Genesis Node...");
        // --- Use DBUtil to save role ---
        dbUtil.saveRole(Role.GENESIS);
        bootstrap.createAddNewNodeRequest(); // Genesis node adds itself
    }

    /**
     * Initializes the node as a Normal node.
     * Sets the role in the database and requests to be added to the bootstrap list.
     */
    public void initNormalNode() {
        ConsolePrinter.printInfo("[NodeInitializer] Initializing as Normal Node...");
        // --- Use DBUtil to save role ---
        dbUtil.saveRole(Role.NORMAL_NODE);
        bootstrap.createAddNewNodeRequest();
    }

    /**
     * Updates the node's role to Leader after a successful promotion.
     * Sets the role in the database and broadcasts the promotion request.
     */
    public void initPromoteNode() {
        ConsolePrinter.printInfo("[NodeInitializer] Promoting node to Leader...");
        // --- Use DBUtil to save role ---
        dbUtil.saveRole(Role.LEADER_NODE);
        bootstrap.createPromoteNodeRequest(); // Broadcast promotion to update others
    }

    // --- Sync methods remain unimplemented placeholders ---
    public void sync() {
        ConsolePrinter.printWarning("[NodeInitializer] Sync functionality not yet implemented.");
    }

    private void resolveSyncResponse() {
        ConsolePrinter.printWarning("[NodeInitializer] Sync response handling not yet implemented.");
    }

    private void resolveSyncRequest() {
        ConsolePrinter.printWarning("[NodeInitializer] Sync request handling not yet implemented.");
    }
}