package org.ddns.consensus;

import org.ddns.node.NodeConfig;

import java.util.Objects;

public class QueueNode {
    NodeConfig nodeConfig;
    int sno;

    public QueueNode(NodeConfig nodeConfig, int sno) {
        this.nodeConfig = nodeConfig;
        this.sno = sno;
    }

    public NodeConfig getNodeConfig() {
        return nodeConfig;
    }

    public void setNodeConfig(NodeConfig nodeConfig) {
        this.nodeConfig = nodeConfig;
    }

    public int getSno() {
        return sno;
    }

    public void setSno(int sno) {
        this.sno = sno;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        QueueNode queueNode = (QueueNode) o;
        return sno == queueNode.sno && Objects.equals(nodeConfig, queueNode.nodeConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeConfig, sno);
    }

    @Override
    public String toString() {
        return "Model{" +
                "nodeConfig=" + nodeConfig +
                ", sno=" + sno +
                '}';
    }
}
