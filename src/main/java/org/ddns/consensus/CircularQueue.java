package org.ddns.consensus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public final class CircularQueue {

    /* =======================
       SINGLETON INSTANCE
       ======================= */
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

    /* =======================
       SHARED STATE
       ======================= */
    private final List<QueueNode> queue;
    private final ReentrantLock lock;
    private int head;

    /* =======================
       PRIVATE CONSTRUCTOR
       ======================= */
    private CircularQueue() {
        this.queue = new ArrayList<>();
        this.lock = new ReentrantLock(true); // fair lock
        this.head = 0;
    }

    /* =======================
       INSTANCE METHODS
       ======================= */

    /**
     * Adds a node in correct position based on sno.
     * Prevents duplicates.
     */
    public void addNode(QueueNode node) {
        if (node == null) {
            throw new IllegalArgumentException("QueueNode cannot be null");
        }

        lock.lock();
        try {
            if (queue.contains(node)) {
                return;
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
                if (insertIndex <= head) {
                    head++;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Round-robin rotation (O(1)).
     */
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

    /**
     * Returns current leader / head.
     */
    public QueueNode peek() {
        lock.lock();
        try {
            return queue.isEmpty() ? null : queue.get(head);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Logical indexed access (relative to head).
     */
    public QueueNode get(int index) {
        lock.lock();
        try {
            if (index < 0 || index >= queue.size()) {
                throw new IndexOutOfBoundsException(
                        "Index " + index + ", size " + queue.size());
            }
            return queue.get((head + index) % queue.size());
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

    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder("CircularQueue{size=");
            sb.append(queue.size()).append(", order=[");
            for (int i = 0; i < queue.size(); i++) {
                sb.append(queue.get((head + i) % queue.size()));
                if (i < queue.size() - 1) sb.append(", ");
            }
            sb.append("]}");
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }
}
