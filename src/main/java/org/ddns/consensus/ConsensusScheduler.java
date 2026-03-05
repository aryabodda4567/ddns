package org.ddns.consensus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

public final class ConsensusScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConsensusScheduler.class);
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public ConsensusScheduler() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "consensus-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start(long intervalSeconds) {
        if (running) return;
        running = true;

        scheduler.scheduleAtFixedRate(
                this::safeConsensusRound,
                0,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private void safeConsensusRound() {
        try {
            ConsensusEngine.runRound();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void skipTurn() {
        QueueNode skipped = CircularQueue.getInstance().peek();
        if (skipped != null) {
            CircularQueue.getInstance().rotate();
            log.info("Skipped leader: {}", skipped.nodeConfig.getIp());
        }
    }
}
