package com.careerplatform.career_assistant.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Tracks failed login attempts and locks out repeated failures.
 *
 * Keyed on email plus client IP, so an attacker can only lock out themselves. Keying on
 * the email alone would let anyone lock any account with five requests and no login.
 *
 * Counters expire fifteen minutes after the last failure, so the window slides: five
 * failures spread over a month never trigger a lock, while a burst does.
 *
 * State is in memory, so limits are per instance — two instances would give an attacker
 * twice the attempts. Moving this to Redis is what fixes that.
 */
@Slf4j
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final long MAX_TRACKED_KEYS = 100_000;

    /** Failure counts per key. Bounded, and entries expire on their own. */
    private final Cache<String, Integer> attempts = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_KEYS)
            .expireAfterWrite(LOCK_DURATION)
            .build();

    /**
     * Locked keys. Presence is the lock and the TTL is the expiry, so there are no
     * timestamps to compare and no unlock path to get wrong.
     */
    private final Cache<String, Boolean> locked = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_KEYS)
            .expireAfterWrite(LOCK_DURATION)
            .build();

    /**
     * Builds the tracking key from a normalised email and the client IP.
     * Always use this rather than building a key by hand — the IP is the part an
     * attacker cannot choose, and it is what keeps the lockout from being a weapon.
     */
    public static String key(String normalisedEmail, String clientIp) {
        return normalisedEmail + "|" + clientIp;
    }

    /** Whether this email and IP combination is currently locked out. */
    public boolean isBlocked(String key) {
        return locked.getIfPresent(key) != null;
    }

    /**
     * Records a failed attempt and locks the key once it reaches the limit.
     * merge() is used so concurrent failures cannot undercount — a read followed by a
     * write would let parallel attempts share a count and get extra tries.
     */
    public void recordFailure(String key) {
        int count = attempts.asMap().merge(key, 1, Integer::sum);

        if (count >= MAX_ATTEMPTS) {
            locked.put(key, Boolean.TRUE);
            log.warn("Login locked for {} minutes after {} consecutive failures (key hash: {})",
                    LOCK_DURATION.toMinutes(), count, key.hashCode());
        } else {
            log.info("Failed login attempt {}/{} (key hash: {})", count, MAX_ATTEMPTS, key.hashCode());
        }
    }

    /** Clears the counter and any lock after a successful login. */
    public void recordSuccess(String key) {
        attempts.invalidate(key);
        locked.invalidate(key);
    }
}
