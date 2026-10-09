package com.careerplatform.career_assistant.ratelimit;

/**
 * Stores the rate-limit state for one caller.
 *
 * The bucket has a maximum number of tokens and continuously
 * adds tokens over time.
 *
 * Each request consumes a certain number of tokens.
 * A request is rejected when there are not enough tokens.
 *
 * This class is not thread-safe, so RateLimiter must update
 * one bucket atomically.
 */
public class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;

    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(int capacity, double refillPerSecond, long nowNanos) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;         // Start with a full bucket so a new caller can make a normal burst of requests.
        this.lastRefillNanos = nowNanos;
    }

    /**
     * Tries to consume the requested number of tokens.
     * Returns true if enough tokens are available.
    */
    public boolean tryConsume(int cost, long nowNanos) {
        refill(nowNanos);

        if (tokens < cost) {
            return false;
        }
        tokens -= cost;
        return true;
    }

    /** Whole tokens currently available — used for the X-RateLimit-Remaining header. */
    public int remaining(long nowNanos) {
        refill(nowNanos);
        return (int) Math.floor(tokens);
    }

    /**
     * Returns the number of seconds until this request has enough tokens.
     * Returns 0 if the request can already be allowed.
     */
    public long secondsUntilAvailable(int cost, long nowNanos) {
        refill(nowNanos);

        if (tokens >= cost) {
            return 0;
        }
        double deficit = cost - tokens;
        return Math.max(1, (long) Math.ceil(deficit / refillPerSecond));
    }

    /**
     * Seconds until the bucket is completely full — used for X-RateLimit-Reset.
     * Different question from secondsUntilAvailable, which asks when one request can
     * go through. This asks when the whole budget is back, which is what a client
     * showing "3 left, resets in 45s" needs.
     */
    /**
     * Returns the number of seconds until this request has enough tokens.
     * Returns 0 if the request can already be allowed.
     */
    public long secondsUntilFull(long nowNanos) {
        refill(nowNanos);

        double deficit = capacity - tokens;
        if (deficit <= 0) {
            return 0;
        }
        return (long) Math.ceil(deficit / refillPerSecond);
    }

    /**
     * Adds tokens for the time elapsed since the last call, capped at capacity.
     * Takes nanoTime rather than wall-clock time on purpose: nanoTime is monotonic,
     * and a clock correction that moved time backwards would subtract tokens.
     */
    /**
     * Adds tokens based on the time elapsed since the last refill.
     * The number of tokens cannot exceed the bucket capacity.
     */
    private void refill(long nowNanos) {
        long elapsedNanos = nowNanos - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return;
        }
        double newTokens = (elapsedNanos / 1_000_000_000.0) * refillPerSecond;
        tokens = Math.min(capacity, tokens + newTokens);
        lastRefillNanos = nowNanos;
    }
}

//**TokenBucket is the actual bucket object that stores the current state of the rate limit ,the maximum number of tokens it can hold, the number of tokens currently available, how quickly tokens are refilled, and when the bucket was last refilled.**

