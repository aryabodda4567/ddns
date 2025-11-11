package org.ddns.governance;

import org.ddns.node.NodeConfig;

import java.util.Objects;

public class Vote {
    NodeConfig nodeConfig;
    boolean isVoted;

    public Vote(NodeConfig nodeConfig, boolean isVoted) {
        this.nodeConfig = nodeConfig;
        this.isVoted = isVoted;
    }

    public NodeConfig getNodeConfig() {
        return nodeConfig;
    }

    public void setNodeConfig(NodeConfig nodeConfig) {
        this.nodeConfig = nodeConfig;
    }

    public boolean isVoted() {
        return isVoted;
    }

    public void setVoted(boolean voted) {
        isVoted = voted;
    }

    @Override
    public String toString() {
        return "Vote{" +
                "nodeConfig=" + nodeConfig +
                ", isVoted=" + isVoted +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Vote vote = (Vote) o;
        return isVoted == vote.isVoted && Objects.equals(nodeConfig, vote.nodeConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeConfig, isVoted);
    }
}
