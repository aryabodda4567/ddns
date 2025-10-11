package org.ddns.net;

import org.ddns.chain.Role;

import java.security.PublicKey;
import java.util.Objects;

public class SystemConfig {
    private String ip;
    private Role role;
    private PublicKey publicKey;

    public SystemConfig(String ip, Role role, PublicKey publicKey) {
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
        if (o == null || getClass() != o.getClass()) return false;
        SystemConfig that = (SystemConfig) o;
        return Objects.equals(getIp(), that.getIp()) && getRole() == that.getRole() && getPublicKey().equals(that.getPublicKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getIp(), getRole(), getPublicKey());
    }
}
