package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.entity.RefreshToken;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.exception.TokenRevokedException;
import com.careerplatform.career_assistant.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    // expiry loaded from application.properties (7 days in seconds)
    @Value("${jwt.refresh-expiration-days:7}")
    private Long refreshExpirationDays;

    /**
     * Generates a raw refresh token, hashes it, saves hash to DB.
     * Raw token sent to frontend — hash stored in DB.
     * If DB is compromised, raw tokens are not exposed.
     */
    @Transactional
    public String generateRefreshToken(User user, String deviceInfo) {
        // generate cryptographically random token
        String rawToken = UUID.randomUUID().toString() + UUID.randomUUID().toString();

        // hash the raw token before saving to DB
        String tokenHash = hashToken(rawToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .tokenHash(tokenHash)
                .user(user)
                .expiryDate(LocalDateTime.now().plusDays(refreshExpirationDays))
                .revoked(false)
                .deviceInfo(deviceInfo)
                .build();

        refreshTokenRepository.save(refreshToken);
        log.info("Refresh token generated for userId: {}", user.getId());

        // return RAW token — this goes to frontend
        // hash is what's stored in DB
        return rawToken;
    }

    /**
     * Validates refresh token during refresh request.
     * Hashes incoming raw token → looks up hash in DB → checks validity.
     * Rotation: deletes old token, generates new one.
     */
    @Transactional
    public String rotateRefreshToken(String rawToken, String deviceInfo) {
        // hash the incoming token to look it up in DB
        String tokenHash = hashToken(rawToken);

        RefreshToken existingToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> {
                    log.warn("Refresh token not found in DB — possible token reuse attack");
                    return new TokenRevokedException("Invalid refresh token");
                });

        // check if token is valid (not revoked, not expired)
        if (!existingToken.isValid()) {
            log.warn("Refresh token is revoked or expired for userId: {}", existingToken.getUser().getId());
            // revoke ALL tokens for this user — possible token theft
            refreshTokenRepository.revokeAllUserTokens(existingToken.getUser());
            throw new TokenRevokedException("Refresh token expired or revoked. Please login again.");
        }

        User user = existingToken.getUser();

        // ROTATION — delete old token, generate new one
        refreshTokenRepository.delete(existingToken);
        log.info("Old refresh token rotated for userId: {}", user.getId());

        // generate and return new raw refresh token
        return generateRefreshToken(user, deviceInfo);
    }

    /**
     * Revokes all refresh tokens for a user.
     * Called on logout — ensures no device can refresh after logout.
     */
    @Transactional
    public void revokeAllTokens(User user) {
        refreshTokenRepository.revokeAllUserTokens(user);
        log.info("All refresh tokens revoked for userId: {}", user.getId());
    }

    /**
     * Deletes all refresh tokens for a user.
     * Called on logout from all devices.
     */
    @Transactional
    public void deleteAllTokens(User user) {
        refreshTokenRepository.deleteByUser(user);
        log.info("All refresh tokens deleted for userId: {}", user.getId());
    }

    // extracts userId from refresh token — used in refresh endpoint
    public Long getUserIdFromToken(String rawToken) {
        String tokenHash = hashToken(rawToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new TokenRevokedException("Invalid refresh token"));
        return token.getUser().getId();
    }

    // SHA-256 hash of raw token — one way, cannot be reversed
    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            log.error("Hashing algorithm not found: {}", ex.getMessage());
            throw new RuntimeException("Error hashing token");
        }
    }
}