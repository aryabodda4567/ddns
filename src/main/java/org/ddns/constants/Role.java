package org.ddns.constants;

public enum Role {
    GENESIS,    // Legacy value; not used for access control
    ANY,        // Wildcard for broadcasting (kept for API compatibility)
    NONE,       // Default role for all nodes in egalitarian mode
    BOOTSTRAP   // Bootstrap server role
}
