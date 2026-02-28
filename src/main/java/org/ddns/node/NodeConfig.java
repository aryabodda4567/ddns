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
        this.role = role != null ? role : Role.NONE;
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

        return Objects.equals(this.ip, that.ip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip);
    }


}
