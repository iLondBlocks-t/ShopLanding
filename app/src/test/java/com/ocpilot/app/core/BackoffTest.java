package com.ocpilot.app.core;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public class BackoffTest {
    @Test public void requiredSequence() { Backoff b = new Backoff(30); for (int expected : new int[]{1,2,4,8,15,30,30,30}) assertEquals(expected, b.nextBaseSeconds()); }
    @Test public void lowerCap() { Backoff b = new Backoff(15); for (int expected : new int[]{1,2,4,8,15,15,15}) assertEquals(expected, b.nextBaseSeconds()); }
    @Test public void sixtySecondCap() { Backoff b = new Backoff(60); for (int expected : new int[]{1,2,4,8,15,30,60,60}) assertEquals(expected, b.nextBaseSeconds()); }
    @Test public void jitterWithinTwentyPercentAndCap() {
        for (int cap : new int[]{15,30,60}) {
            Backoff b = new Backoff(cap, new Random(1234)), baseline = new Backoff(cap);
            for (int i = 0; i < 100; i++) { long base = baseline.nextBaseSeconds() * 1000L, actual = b.nextMillis(); assertTrue(actual >= Math.round(base * .8)); assertTrue(actual <= Math.round(base * 1.2)); assertTrue(actual <= cap * 1000L); }
        }
    }
    @Test public void jitterBoundaries() {
        Backoff low = new Backoff(30, new Random() { @Override public double nextDouble() { return 0; } });
        Backoff high = new Backoff(30, new Random() { @Override public double nextDouble() { return 1; } });
        assertEquals(800, low.nextMillis()); assertEquals(1200, high.nextMillis());
    }
    @Test public void reset() { Backoff b = new Backoff(30); b.nextMillis(); b.nextMillis(); b.reset(); assertEquals(0, b.attempts()); assertEquals(1, b.nextBaseSeconds()); }
}
