package com.careerplatform.career_assistant.repository;

import com.careerplatform.career_assistant.entity.RefreshToken;
import com.careerplatform.career_assistant.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Finds a token by hash and loads its user in the same query.
     * Used during refresh and logout, where the user is always needed.
     */
    @Query("SELECT rt FROM RefreshToken rt JOIN FETCH rt.user WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashWithUser(@Param("tokenHash") String tokenHash);

    /**
     * Revokes every token in a rotation chain.
     * Called when a revoked token is replayed, which means a copy of it exists somewhere
     * it should not.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true, rt.revokedAt = CURRENT_TIMESTAMP " +
           "WHERE rt.familyId = :familyId AND rt.revoked = false")
    int revokeFamily(@Param("familyId") String familyId);

    // revoke all active tokens for a user - logout, password change, suspected compromise
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true, rt.revokedAt = CURRENT_TIMESTAMP " +
           "WHERE rt.user = :user AND rt.revoked = false")
    int revokeAllUserTokens(@Param("user") User user);

    /**
     * A user's active sessions, newest first. Used to enforce the session cap.
     * Without a cap this table grows by one usable credential per login, forever.
     */
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.user = :user AND rt.revoked = false " +
           "AND rt.expiryDate > CURRENT_TIMESTAMP ORDER BY rt.createdAt DESC")
    List<RefreshToken> findActiveByUserNewestFirst(@Param("user") User user);

    /**
     * Deletes rows expired before the cutoff. Run nightly by RefreshTokenService.
     * The cutoff includes a grace period so a replayed token still finds its revoked row
     * and triggers family revocation, instead of degrading to a generic "not found".
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiryDate < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Hard-deletes a user's tokens. Account deletion only.
     * Logout revokes instead, because deleting the rows would throw away the evidence
     * that makes reuse detection work.
     */
    @Modifying
    void deleteByUserId(Long userId);
}
