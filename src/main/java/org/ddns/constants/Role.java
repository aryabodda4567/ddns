package org.ddns.constants;

public enum Role {
    GENESIS, // First node only (marker)
    ANY, // Wildcard for broadcasting
    NONE, // Unassigned
    BOOTSTRAP // Bootstrap server role
}
