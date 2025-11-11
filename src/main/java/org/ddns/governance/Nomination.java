package org.ddns.governance;

import org.ddns.constants.ElectionType;
import org.ddns.node.NodeConfig;

import java.util.Objects;

public class Nomination {
    private NodeConfig nodeConfig;
    private Boolean vote;
    private long startTime;
    private long expireTime;
    private String ipAddress;
    private ElectionType electionType;
    private String nodeName;
    private String description;

    public Nomination(NodeConfig nodeConfig, Boolean vote, long startTime, long expireTime, String ipAddress, ElectionType electionType, String nodeName, String description) {
        this.nodeConfig = nodeConfig;
        this.vote = vote;
        this.startTime = startTime;
        this.expireTime = expireTime;
        this.ipAddress = ipAddress;
        this.electionType = electionType;
        this.nodeName = nodeName;
        this.description = description;
    }

    public NodeConfig getNodeConfig() {
        return nodeConfig;
    }

    public void setNodeConfig(NodeConfig nodeConfig) {
        this.nodeConfig = nodeConfig;
    }

    public Boolean getVote() {
        return vote;
    }

    public void setVote(Boolean vote) {
        this.vote = vote;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(long expireTime) {
        this.expireTime = expireTime;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public ElectionType getElectionType() {
        return electionType;
    }

    public void setElectionType(ElectionType electionType) {
        this.electionType = electionType;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return "Nomination{" +
                "nodeConfig=" + nodeConfig +
                ", vote=" + vote +
                ", startTime=" + startTime +
                ", expireTime=" + expireTime +
                ", ipAddress='" + ipAddress + '\'' +
                ", electionType=" + electionType +
                ", nodeName='" + nodeName + '\'' +
                ", description='" + description + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Nomination that = (Nomination) o;
        return startTime == that.startTime &&
                expireTime == that.expireTime &&
                Objects.equals(nodeConfig, that.nodeConfig) &&
                Objects.equals(ipAddress, that.ipAddress) &&
                electionType == that.electionType &&
                Objects.equals(nodeName, that.nodeName) &&
                Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeConfig, startTime, expireTime, ipAddress, electionType, nodeName, description);
    }

}