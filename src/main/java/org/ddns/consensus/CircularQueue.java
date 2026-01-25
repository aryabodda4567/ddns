package org.ddns.consensus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public final class CircularQueue {

    private static volatile CircularQueue INSTANCE;

    public static CircularQueue getInstance() {
        if (INSTANCE == null) {
            synchronized (CircularQueue.class) {
                if (INSTANCE == null) {
                    INSTANCE = new CircularQueue();
                }
            }
        }
        return INSTANCE;
    }

    private final List<QueueNode> queue;
    private final ReentrantLock lock;
    private int head;

    private CircularQueue() {
        this.queue = new ArrayList<>();
        this.lock = new ReentrantLock(true);
        this.head = 0;
    }

    public void addNode(QueueNode node) {
        if (node == null) throw new IllegalArgumentException("QueueNode cannot be null");

        lock.lock();
        try {
            for (QueueNode q : queue) {
                if (q.nodeConfig.equals(node.nodeConfig)) return;
            }

            if (queue.isEmpty()) {
                queue.add(node);
                head = 0;
                return;
            }

            int insertIndex = -1;
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).sno + 1 == node.sno) {
                    insertIndex = i + 1;
                    break;
                }
            }

            if (insertIndex == -1) {
                queue.add(node);
            } else {
                queue.add(insertIndex, node);
                if (insertIndex <= head) head++;
            }
        } finally {
            lock.unlock();
        }
    }

    public void rotate() {
        lock.lock();
        try {
            if (!queue.isEmpty()) {
                head = (head + 1) % queue.size();
            }
        } finally {
            lock.unlock();
        }
    }

    public QueueNode peek() {
        lock.lock();
        try {
            return queue.isEmpty() ? null : queue.get(head);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }
    public void resetWith(List<QueueNode> nodes) {
        lock.lock();
        try {
            queue.clear();
            head = 0;

            nodes.sort(Comparator.comparingInt(QueueNode::getSno));

            queue.addAll(nodes);
        } finally {
            lock.unlock();
        }
    }

}
