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

    public int getSno() {
        return sno;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QueueNode)) return false;
        QueueNode q = (QueueNode) o;
        return sno == q.sno && Objects.equals(nodeConfig, q.nodeConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeConfig, sno);
    }

    @Override
    public String toString() {
        return "QueueNode{" + nodeConfig + ", sno=" + sno + '}';
    }
}
