package org.ddns.node;

import org.ddns.constants.Role;

import java.security.PublicKey;
import java.util.Objects;

public class NodeConfig {
    private String ip;
    private Role role;
    private PublicKey publicKey;

    public NodeConfig(String ip, Role role, PublicKey publicKey) {
        this.ip = ip;
        this.role = role;
        this.publicKey = publicKey;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    @Override
    public String toString() {
        return "SystemConfig{" +
                "ip='" + ip + '\'' +
                ", role=" + role +
                ", public key=" + publicKey +
                '}';
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NodeConfig that = (NodeConfig) o;

        boolean ipEquals = Objects.equals(this.ip, that.ip);
        boolean roleEquals = this.role == that.role;

        // Normalize keys (remove whitespace and newlines)
//        String thisKey = this.publicKey != null ? SignatureUtil.getStringFromKey(this.publicKey).replaceAll("\\s+", "") : null;
//        String thatKey = that.publicKey != null ? SignatureUtil.getStringFromKey(that.publicKey).replaceAll("\\s+", "") : null;
//        boolean keyEquals = Objects.equals(thisKey, thatKey);

        return ipEquals && roleEquals;//&& keyEquals;
    }

    @Override
    public int hashCode() {
//        String keyStr = publicKey != null ? SignatureUtil.getStringFromKey(publicKey).replaceAll("\\s+", "") : null;
        return Objects.hash(ip, role);
    }


}
