package org.ddns.consensus;

public class LivenessController {

    private final long timeoutMs;
    private long lastBlockTimestamp;

    public LivenessController(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.lastBlockTimestamp = System.currentTimeMillis();
    }

    public void resetTimer() {
        this.lastBlockTimestamp = System.currentTimeMillis();
    }

    public void checkLiveness(ConsensusScheduler scheduler) {
        long now = System.currentTimeMillis();
        long elapsed = now - lastBlockTimestamp;

        if (elapsed > timeoutMs && !ConsensusEngine.hasNoPendingTransactions()) {
            scheduler.skipTurn();
            this.lastBlockTimestamp = now;
        }
    }
}
