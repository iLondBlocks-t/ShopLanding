package com.ocpilot.app.core;

import java.util.Random;

public final class Backoff {
    private static final int[] STEPS = { 1, 2, 4, 8, 15, 30 };
    private final int capSeconds;
    private final Random random;
    private int attempt;
    public Backoff(int capSeconds) { this(capSeconds, new Random()); }
    public Backoff(int capSeconds, Random random) {
        this.capSeconds = Math.max(1, capSeconds);
        this.random = random;
    }
    public int attempts() { return attempt; }
    public int nextBaseSeconds() {
        int index = attempt++;
        long value = index < STEPS.length ? STEPS[index] : 30L << Math.min(index - STEPS.length + 1, 10);
        return (int) Math.min(capSeconds, value);
    }
    public long nextMillis() {
        long base = nextBaseSeconds() * 1000L;
        return Math.min(capSeconds * 1000L, Math.max(1L, Math.round(base * (0.8 + random.nextDouble() * 0.4))));
    }
    public void reset() { attempt = 0; }
}
