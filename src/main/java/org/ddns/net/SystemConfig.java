package org.ddns.net;

import org.ddns.chain.Role;

import java.util.Objects;

public class SystemConfig {
    private String ip;
    private Role role;

    public SystemConfig(String ip, Role role) {
        this.ip = ip;
        this.role = role;
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
        return Objects.equals(getIp(), that.getIp()) && getRole() == that.getRole();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getIp(), getRole());
    }
}
