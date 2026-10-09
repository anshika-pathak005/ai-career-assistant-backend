package com.careerplatform.career_assistant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing blacklisted JWT access tokens upon logout.
 * Keeps track of revoked tokens in memory until their natural expiry time.
 */
@Slf4j
@Service
public class TokenBlacklistService {

    // thread-safe map storing blacklisted tokens and their expiration timestamps (in milliseconds)
    private final Map<String, Long> blacklistedTokens = new ConcurrentHashMap<>();

    /**
     * Blacklists a token until its expiration timestamp.
     */
    public void blacklistToken(String token, Long expirationTimeMillis) {
        if (token != null && !token.isBlank()) {
            blacklistedTokens.put(token, expirationTimeMillis);
            log.info("Token blacklisted successfully until: {}", expirationTimeMillis);
        }
    }

    /**
     * Checks if a token is present in the blacklist and hasn't naturally expired yet.
     */
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        Long expiry = blacklistedTokens.get(token);
        if (expiry == null) {
            return false;
        }

        // if token expiry time has passed, it is no longer valid anyway
        if (System.currentTimeMillis() > expiry) {
            blacklistedTokens.remove(token);
            return false;
        }

        return true;
    }

    /**
     * Periodic scheduled task running every 30 minutes to clean up expired blacklisted tokens.
     * Prevents memory leaks over long server uptime.
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes in milliseconds
    public void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();
        int initialSize = blacklistedTokens.size();

        blacklistedTokens.entrySet().removeIf(entry -> now > entry.getValue());

        int removed = initialSize - blacklistedTokens.size();
        if (removed > 0) {
            log.info("Cleaned up {} expired tokens from blacklist", removed);
        }
    }
}
