package com.careerplatform.career_assistant.ratelimit;

/**
 * The rate limit buckets available to endpoints, with their budgets.
 * Capacity is how large a burst is tolerated; refill is the sustained rate.
 * An endpoint picks its tier with the @RateLimit annotation.
 */
public enum RateLimitTier {

    /**
     * Login and registration. Keyed by IP, since there is no authenticated user yet.
     * Tight, because this is the brute-force surface: capacity allows a few typos and
     * a retry, while the refill rate makes a password-guessing loop useless.
     */
    AUTH(10, 10.0 / 60.0),

    /**
     * Endpoints that call Groq. Keyed by userId.
     * Spent in cost units rather than request counts, so the budget bounds API spend
     * rather than traffic — see RateLimit.cost.
     */
    AI(30, 30.0 / 60.0),

    /** Ordinary authenticated reads. Generous, since these are cheap. */
    GENERAL(100, 100.0 / 60.0);

    private final int capacity;
    private final double refillPerSecond;

    RateLimitTier(int capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    public int capacity() {
        return capacity;
    }

    public double refillPerSecond() {
        return refillPerSecond;
    }

    public TokenBucket newBucket(long nowNanos) {
        return new TokenBucket(capacity, refillPerSecond, nowNanos);
    }
}
