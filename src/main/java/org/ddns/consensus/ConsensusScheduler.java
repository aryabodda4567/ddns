package org.ddns.consensus;


import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ConsensusScheduler {

    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public ConsensusScheduler() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "consensus-scheduler");
            t.setDaemon(true); // background thread
            return t;
        });
    }

    /**
     * Starts consensus rounds at fixed intervals.
     */
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

    /**
     * Wrap consensus logic with safety.
     */
    private void safeConsensusRound() {
        try {
            ConsensusEngine.runRound();
        } catch (Throwable t) {
            // MUST catch everything — otherwise scheduler dies
            System.err.println("Consensus round failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Graceful shutdown.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        scheduler.shutdown();
    }
}
