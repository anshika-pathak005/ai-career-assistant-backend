package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.config.properties.JwtProperties;
import com.careerplatform.career_assistant.entity.RefreshToken;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.TokenRevokedException;
import com.careerplatform.career_assistant.repository.RefreshTokenRepository;
import com.careerplatform.career_assistant.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * Tokens are stored as SHA-256 hashes, so a leaked database yields nothing usable.
 * Each login starts a token family; rotation issues a successor within the same family
 * and marks the old token revoked rather than deleting it. A revoked token being
 * presented again therefore means someone holds a copy, and the whole family is burned.
 *
 * Users are capped at five concurrent sessions, and long-expired rows are purged nightly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * Runs the theft response in its own transaction.
     *
     * Reuse is signalled by throwing, which marks the surrounding transaction for
     * rollback — the revocation would be rolled back with it and the stolen family
     * would stay live. REQUIRES_NEW commits it independently.
     *
     * A TransactionTemplate rather than a @Transactional(REQUIRES_NEW) method because a
     * self-invoked method never passes through the Spring proxy, so the annotation
     * would be silently ignored.
     */
    private TransactionTemplate revocationTx;

    /** Maximum concurrent sessions per user; the oldest is revoked beyond this. */
    private static final int MAX_ACTIVE_SESSIONS = 5;

    /**
     * How long a fully expired row is kept before deletion.
     * A replayed token must still find its row to trigger family revocation, so
     * deleting eagerly would turn a real theft into a quiet "invalid token".
     */
    private static final int PURGE_GRACE_DAYS = 30;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 6.4: was a second, independent @Value for jwt.refresh-expiration-days - CookieUtil
     * declared its own copy with its own default. Two defaults for one setting is how a
     * cookie outlives the row it points at, or the reverse.
     */
    private final JwtProperties jwtProperties;

    @PostConstruct
    void initRevocationTx() {
        this.revocationTx = new TransactionTemplate(transactionManager);
        this.revocationTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Result of a rotation: the new raw token plus the user it belongs to.
     * The user is returned so the caller does not have to look the same row up again.
     */
    public record RotationResult(String rawToken, User user) {}

    /**
     * Issues a brand new refresh token for a fresh login, starting a new family.
     * Returns the raw token, which goes to the browser; only its hash is stored.
     */
    @Transactional
    public String generateRefreshToken(User user, String deviceInfo) {
        String rawToken = newRawToken();
        persist(user, rawToken, UUID.randomUUID().toString(), deviceInfo);
        enforceSessionCap(user);
        log.info("New refresh token family started for userId: {}", user.getId());
        return rawToken;
    }

    /**
     * Rotates a refresh token, issuing a successor in the same family.
     *
     * Three outcomes:
     *   unknown hash      — generic invalid-token error
     *   known but revoked — the token was already rotated away, so a second party holds
     *                       a copy. Burn the whole family and force a fresh login.
     *   known and valid   — issue the successor and mark this one rotated
     */
    @Transactional
    public RotationResult rotateRefreshToken(String rawToken, String deviceInfo) {
        String tokenHash = hashToken(rawToken);

        RefreshToken existing = refreshTokenRepository.findByTokenHashWithUser(tokenHash)
                .orElseThrow(() -> {
                    log.warn("Refresh token not found");
                    return new TokenRevokedException("Invalid refresh token");
                });

        User user = existing.getUser();

        // Replay. We cannot tell which party is the attacker, so both lose the session —
        // the attacker has no credentials to get it back, the real user does.
        if (existing.isRevoked()) {
            burnFamily(existing.getFamilyId(), user.getId());
            throw new TokenRevokedException("Session security check failed. Please log in again.");
        }

        if (existing.isExpired()) {
            // Ordinary expiry, not theft — do not burn the family for it.
            log.info("Refresh token expired for userId: {}", user.getId());
            throw new TokenRevokedException("Session expired. Please log in again.");
        }

        // Rows written before families existed have none. Adopt them into a fresh one
        // rather than logging the user out over a schema change.
        String familyId = existing.getFamilyId() != null
                ? existing.getFamilyId()
                : UUID.randomUUID().toString();

        String newRawToken = newRawToken();
        String newHash = hashToken(newRawToken);

        persist(user, newRawToken, familyId, deviceInfo);

        // Mark rotated rather than deleting. This surviving row is what makes the
        // replay branch above reachable at all.
        existing.revokeReplacedBy(newHash);
        refreshTokenRepository.save(existing);

        log.info("Refresh token rotated for userId: {} (family {})", user.getId(), familyId);
        return new RotationResult(newRawToken, user);
    }

    /**
     * Revokes all of a user's refresh tokens, keeping the rows.
     * Called on logout and password change.
     */
    @Transactional
    public void revokeAllTokens(User user) {
        int revoked = refreshTokenRepository.revokeAllUserTokens(user);
        log.info("Revoked {} refresh token(s) for userId: {}", revoked, user.getId());
    }

    /**
     * Resolves the owning userId from a raw refresh token, or null if unknown.
     * Used by logout, which must not throw — an unrecognised cookie should still give a
     * clean 204 rather than an error that reveals whether the token was real.
     * Revoked and expired rows still resolve, since revoking again is harmless.
     */
    @Transactional(readOnly = true)
    public Long findUserIdOrNull(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return refreshTokenRepository.findByTokenHashWithUser(hashToken(rawToken))
                .map(token -> token.getUser().getId())
                .orElse(null);
    }

    /**
     * Nightly cleanup of rows whose expiry is well past.
     * Rotation never deletes anything, so without this the table only grows.
     * Requires @EnableScheduling on the application class.
     */
    @Scheduled(cron = "${app.refresh-token.purge-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpiredTokens() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(PURGE_GRACE_DAYS);
        int deleted = refreshTokenRepository.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} refresh token(s) expired before {}", deleted, cutoff);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Revokes a whole rotation chain and invalidates the user's access tokens.
     * tokenVersion is bumped as well as the refresh family, because revoking only the
     * refresh tokens would leave any access token the attacker already minted working
     * until it expired.
     */
    private void burnFamily(String familyId, Long userId) {
        revocationTx.executeWithoutResult(status -> {
            int burned = refreshTokenRepository.revokeFamily(familyId);
            userRepository.bumpTokenVersion(userId);
            log.error("REFRESH TOKEN REUSE DETECTED for userId: {} - revoked {} token(s) " +
                      "in family {} and invalidated all access tokens", userId, burned, familyId);
        });
    }

    /** Saves a new token row for a user within the given family. */
    private void persist(User user, String rawToken, String familyId, String deviceInfo) {
        refreshTokenRepository.save(RefreshToken.builder()
                .tokenHash(hashToken(rawToken))
                .user(user)
                .familyId(familyId)
                .expiryDate(LocalDateTime.now().plusDays(jwtProperties.refreshExpirationDays()))
                .revoked(false)
                .deviceInfo(deviceInfo)
                .build());
    }

    /**
     * Revokes the oldest sessions once a user is over the cap.
     * Only the individual oldest tokens are revoked, not whole families — hitting the
     * cap is normal behaviour, not a theft signal.
     */
    private void enforceSessionCap(User user) {
        List<RefreshToken> active = refreshTokenRepository.findActiveByUserNewestFirst(user);
        if (active.size() <= MAX_ACTIVE_SESSIONS) {
            return;
        }
        List<RefreshToken> surplus = active.subList(MAX_ACTIVE_SESSIONS, active.size());
        surplus.forEach(token -> {
            token.setRevoked(true);
            token.setRevokedAt(LocalDateTime.now());
        });
        refreshTokenRepository.saveAll(surplus);
        log.info("Session cap reached for userId: {} - revoked {} oldest session(s)",
                user.getId(), surplus.size());
    }

    /** Generates a raw refresh token: 256 bits from SecureRandom, URL-safe base64. */
    private String newRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 of the raw token — one way, so a leaked database yields no usable tokens.
     * A plain hash is correct here and BCrypt would be wrong: the input is already 256
     * bits of randomness, so there is nothing to brute force and a slow hash on every
     * refresh would just be latency.
     */
    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandatory in every JRE; if it is missing the platform is broken
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
