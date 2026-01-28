package org.ddns.dns;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DNSExecutor {
    public static final ExecutorService POOL =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4);
}

