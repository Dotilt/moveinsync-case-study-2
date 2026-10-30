package com.moveinsync.config.client.sync;

import java.util.concurrent.ThreadLocalRandom;

public class BackoffStrategy {
    private final long baseMillis;
    private final long maxMillis;
    private final double jitterFactor;
    private int attempt;

    public BackoffStrategy(long baseMillis, long maxMillis, double jitterFactor) {
        this.baseMillis = baseMillis;
        this.maxMillis = maxMillis;
        this.jitterFactor = jitterFactor;
        this.attempt = 0;
    }

    public static BackoffStrategy defaultStrategy() {
        return new BackoffStrategy(500, 10000, 0.5);
    }

    public synchronized long nextDelayMillis() {
        long exponential = baseMillis * (1L << Math.min(attempt, 20));
        long capped = Math.min(exponential, maxMillis);
        attempt++;

        // Add randomized jitter: e.g. [1 - jitterFactor, 1 + jitterFactor]
        double minMultiplier = Math.max(0.1, 1.0 - jitterFactor);
        double maxMultiplier = 1.0 + jitterFactor;
        double multiplier = ThreadLocalRandom.current().nextDouble(minMultiplier, maxMultiplier);

        return Math.max(10, (long) (capped * multiplier));
    }

    public synchronized void reset() {
        this.attempt = 0;
    }

    public synchronized int getAttempt() {
        return attempt;
    }
}
