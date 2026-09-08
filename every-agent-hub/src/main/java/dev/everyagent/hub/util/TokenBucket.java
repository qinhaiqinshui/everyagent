package dev.everyagent.hub.util;

/**
 * 简单令牌桶(synchronized,量级完全够用)。
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastNanos = System.nanoTime();

    public TokenBucket(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
    }

    public synchronized boolean tryConsume(int n) {
        long now = System.nanoTime();
        double elapsed = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        tokens = Math.min(capacity, tokens + elapsed * refillPerSecond);
        if (tokens >= n) {
            tokens -= n;
            return true;
        }
        return false;
    }
}
