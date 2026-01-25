package org.ddns.consensus;

public final class ConsensusSystem {

    private static final ConsensusScheduler scheduler = new ConsensusScheduler();

    public static void start() {
        scheduler.start(3); // every 3 seconds
    }

    public static ConsensusScheduler getScheduler() {
        return scheduler;
    }
}
