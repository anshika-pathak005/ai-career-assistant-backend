package com.careerplatform.career_assistant.unit;

import com.careerplatform.career_assistant.ratelimit.TokenBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan item 3 — the token bucket itself.
 *
 * <b>No Spring context.</b> These run in milliseconds, which matters more than it sounds:
 * a suite where every test costs a Hibernate schema build is a suite people stop running
 * locally, and then only find out at push time.
 *
 * The reason this class is testable at all is that TokenBucket takes `nowNanos` as a
 * parameter instead of calling System.nanoTime() internally. Time as an argument means
 * refill behaviour can be checked by passing a number rather than by sleeping. Every
 * Thread.sleep in a test suite is a future flaky failure on a loaded CI runner.
 */
@DisplayName("3 — token bucket refill and burst behaviour")
class TokenBucketTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    @DisplayName("A full bucket allows a burst up to its capacity, then refuses")
    void allowsBurstUpToCapacity() {
        long now = 0L;
        TokenBucket bucket = new TokenBucket(10, 10.0 / 60.0, now);

        for (int i = 1; i <= 10; i++) {
            assertThat(bucket.tryConsume(1, now))
                    .as("request %d of the burst", i)
                    .isTrue();
        }
        assertThat(bucket.tryConsume(1, now))
                .as("the 11th request must be refused")
                .isFalse();
    }

    @Test
    @DisplayName("Tokens come back at the configured rate, not all at once")
    void refillsGradually() {
        long now = 0L;
        // 1 token per second, capacity 5.
        TokenBucket bucket = new TokenBucket(5, 1.0, now);

        for (int i = 0; i < 5; i++) {
            bucket.tryConsume(1, now);
        }
        assertThat(bucket.tryConsume(1, now)).isFalse();

        // Half a second is not enough for a whole token.
        assertThat(bucket.tryConsume(1, now + SECOND / 2)).isFalse();
        // A full second is.
        assertThat(bucket.tryConsume(1, now + SECOND)).isTrue();
    }

    @Test
    @DisplayName("Refill never exceeds capacity")
    void doesNotAccumulateBeyondCapacity() {
        long now = 0L;
        TokenBucket bucket = new TokenBucket(5, 1.0, now);

        // An hour of idleness. Without a cap, the bucket would hold 3600 tokens and the
        // first burst after a quiet period would sail straight past the limit — the
        // failure mode is invisible under continuous load and appears at 3am.
        long muchLater = now + TimeUnit.HOURS.toNanos(1);

        for (int i = 0; i < 5; i++) {
            assertThat(bucket.tryConsume(1, muchLater)).isTrue();
        }
        assertThat(bucket.tryConsume(1, muchLater))
                .as("capacity is a ceiling, not a starting point")
                .isFalse();
    }

    @Test
    @DisplayName("Cost is spent in units, so an expensive call drains more")
    void costIsDeductedInUnits() {
        long now = 0L;
        TokenBucket bucket = new TokenBucket(10, 1.0, now);

        // This is what makes the AI tier bound API SPEND rather than request count: a
        // roadmap generation costs more than a cheap read.
        assertThat(bucket.tryConsume(8, now)).isTrue();
        assertThat(bucket.remaining(now)).isEqualTo(2);
        assertThat(bucket.tryConsume(8, now))
                .as("a second expensive call cannot be afforded")
                .isFalse();
        assertThat(bucket.tryConsume(2, now))
                .as("but a cheap one still can")
                .isTrue();
    }

    @Test
    @DisplayName("A refused request reports a sane Retry-After")
    void retryAfterIsPositiveWhenRefused() {
        long now = 0L;
        TokenBucket bucket = new TokenBucket(2, 1.0, now);
        bucket.tryConsume(2, now);

        // Retry-After carries a real number so a client waits instead of hammering, which
        // would turn one rejection into a loop of them.
        assertThat(bucket.secondsUntilAvailable(1, now)).isPositive();
        assertThat(bucket.secondsUntilFull(now)).isPositive();
    }
}
