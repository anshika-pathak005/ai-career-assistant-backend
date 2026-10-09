package com.careerplatform.career_assistant.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Manages rate-limit buckets and checks whether requests are allowed.
 *
 * It is used by both rate-limiting layers:
 * - RateLimitingFilter: limits requests by IP before authentication.
 * - RateLimitInterceptor: limits requests by user after authentication.
 *
 * A bucket is created when it is first needed and removed after being idle.
 */
@Component
public class RateLimiter {

    /**
     * Stores rate-limit buckets in memory.
     *
     * The cache is limited in size so many unique keys cannot use unlimited memory.
     * Unused buckets are removed after 10 minutes.
     */
    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000) // Limit the number of stored buckets so a large number of unique keys cannot keep increasing memory usage.
            .expireAfterAccess(Duration.ofMinutes(10)) // Remove buckets that have not been used for 10 minutes. This only controls how long the bucket stays in memory ,token refill is handled by TokenBucket.
            .build();

    /** Outcome of a rate limit check, carrying everything the response headers need. */
    /** Stores the result of a rate-limit check. */
    public record Decision(boolean allowed, int limit, int remaining,
                           long retryAfterSeconds, long resetSeconds) {}

    /**
     * Attempts to spend `cost` tokens from the bucket identified by tier and key.
     * All mutation happens inside Caffeine's per-key atomic compute block, because
     * TokenBucket is not thread-safe and a read-modify-write outside it would let two
     * threads both decide they can afford the same tokens.
     */
    public Decision tryConsume(RateLimitTier tier, String key, int cost) {
        // Create a separate bucket key for each rate-limit tier and user/IP.
        String bucketKey = tier.name() + ":" + key;
        // Get a monotonic timestamp used to calculate token refills. or How much time has passed since the last refill?
        long nowNanos = System.nanoTime();

        // These values are filled inside the atomic cache operation
        // and used to create the final Decision afterward.
        boolean[] allowed = new boolean[1];
        int[] remaining = new int[1];
        long[] retryAfter = new long[1];
        long[] resetIn = new long[1];

        buckets.asMap().compute(bucketKey, (ignored, bucket) -> {
            // Reuse the existing bucket or create a new one if this key is used for the first time.
            TokenBucket target = (bucket != null) ? bucket : tier.newBucket(nowNanos);

            allowed[0] = target.tryConsume(cost, nowNanos);
            remaining[0] = target.remaining(nowNanos);
            // If the request was rejected, calculate how long until enough tokens are available for this request.
            retryAfter[0] = allowed[0] ? 0 : target.secondsUntilAvailable(cost, nowNanos);
            resetIn[0] = target.secondsUntilFull(nowNanos);

            return target;
        });
        // Return the decision, which includes whether the request is allowed, the number of remaining tokens, the time to wait before retrying, and the time until the rate limit resets.
        return new Decision(allowed[0], tier.capacity(), remaining[0], retryAfter[0], resetIn[0]);
    }
}
