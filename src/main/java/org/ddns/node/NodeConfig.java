package org.ddns.node;

import org.ddns.constants.Role;

import java.security.PublicKey;
import java.util.Objects;

/**
 * NodeConfig represents a node in the distributed DNS network.
 * <p>
 * It is used for:
 * - Node identity verification
 * - Network membership validation
 * - HashSet / HashMap membership checks
 * <p>
 * IMPORTANT:
 * This class overrides equals() and hashCode() so it can safely
 * be used inside HashSet and HashMap collections.
 */
public class NodeConfig {

    /**
     * Node IP address
     */
    private String ip;

    /**
     * Node role (BOOTSTRAP / VALIDATOR / NONE etc.)
     */
    private Role role;

    /**
     * Node public key used for cryptographic identity
     */
    private PublicKey publicKey;

    /**
     * Constructor
     *
     * @param ip        node IP address
     * @param role      node role (defaults to NONE if null)
     * @param publicKey node public key
     */
    public NodeConfig(String ip, Role role, PublicKey publicKey) {

        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("IP cannot be null or empty");
        }

        if (publicKey == null) {
            throw new IllegalArgumentException("PublicKey cannot be null");
        }

        this.ip = ip;
        this.role = role != null ? role : Role.NONE;
        this.publicKey = publicKey;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(PublicKey publicKey) {
        if (publicKey == null) {
            throw new IllegalArgumentException("PublicKey cannot be null");
        }
        this.publicKey = publicKey;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("IP cannot be null or empty");
        }
        this.ip = ip;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role != null ? role : Role.NONE;
    }

    /**
     * String representation useful for debugging and logging.
     */
    @Override
    public String toString() {
        return "NodeConfig{" +
                "ip='" + ip + '\'' +
                ", role=" + role +
                ", publicKey=" + publicKey +
                '}';
    }

    /**
     * Equality check used by HashSet.contains() and HashMap lookups.
     * <p>
     * Two NodeConfig objects are considered equal if:
     * - IP matches
     * - PublicKey matches
     * - Role matches
     */
    @Override
    public boolean equals(Object o) {

        if (this == o) return true;

        if (!(o instanceof NodeConfig)) return false;

        NodeConfig that = (NodeConfig) o;

        return Objects.equals(this.ip, that.ip)
                && Objects.equals(this.publicKey, that.publicKey)
                && this.role == that.role;
    }

    /**
     * Hash code used by HashSet / HashMap bucket placement.
     * <p>
     * Must be consistent with equals().
     */
    @Override
    public int hashCode() {
        return Objects.hash(ip, publicKey, role);
    }
}