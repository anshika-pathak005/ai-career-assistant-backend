package com.careerplatform.career_assistant.repository;

import com.careerplatform.career_assistant.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Find user by email (for login)
     */
    Optional<User> findByEmail(String email);

    /**
     * Check if email already exists (for registration)
     */
    boolean existsByEmail(String email);

    /**
     * Invalidates every access token this user holds, by bumping their token version.
     * Called on logout and password change.
     *
     * Atomic rather than load-increment-save, so two concurrent revocations cannot read
     * the same value and write the same result, leaving one of them with no effect.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.tokenVersion = u.tokenVersion + 1 WHERE u.id = :userId")
    int bumpTokenVersion(@Param("userId") Long userId);

    /**
     * Accounts whose deletion grace period has run out and which are now due to be
     * destroyed. Read by the scheduled purge in UserSettingsService.
     *
     * 7.1: returns whole entities rather than ids because the purge needs the row to
     * delete it and to log what was removed, and the result set is bounded by how many
     * people asked to leave in one window - a handful, not a table scan's worth.
     */
    List<User> findByDeletionRequestedAtLessThanEqual(LocalDateTime cutoff);

    /**
     * Consumes one roadmap generation from the user's monthly quota.
     * Returns 0 rows if the limit was already reached.
     *
     * The limit check lives in the WHERE clause, so the check and the increment are one
     * atomic statement and concurrent requests cannot both pass the same limit.
     *
     * The month rollover is folded in here too: as a separate read-modify-write, two
     * requests on the 1st could both see a stale month, both reset to 0, and both
     * consume the first unit.
     *
     * PRO users skip the limit but are still counted, so usage reporting stays accurate.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE User u SET
                u.roadmapsGeneratedThisMonth = CASE WHEN u.currentMonthYear = :month
                    THEN u.roadmapsGeneratedThisMonth + 1 ELSE 1 END,
                u.resumesAnalyzedThisMonth = CASE WHEN u.currentMonthYear = :month
                    THEN u.resumesAnalyzedThisMonth ELSE 0 END,
                u.currentMonthYear = :month
            WHERE u.id = :userId
              AND (u.plan = com.careerplatform.career_assistant.enums.Plan.PRO
                   OR u.currentMonthYear IS NULL
                   OR u.currentMonthYear <> :month
                   OR u.roadmapsGeneratedThisMonth < :limit)
            """)
    int tryConsumeRoadmapQuota(@Param("userId") Long userId,
                               @Param("month") String month,
                               @Param("limit") int limit);

    /** Resume analysis equivalent of tryConsumeRoadmapQuota. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE User u SET
                u.resumesAnalyzedThisMonth = CASE WHEN u.currentMonthYear = :month
                    THEN u.resumesAnalyzedThisMonth + 1 ELSE 1 END,
                u.roadmapsGeneratedThisMonth = CASE WHEN u.currentMonthYear = :month
                    THEN u.roadmapsGeneratedThisMonth ELSE 0 END,
                u.currentMonthYear = :month
            WHERE u.id = :userId
              AND (u.plan = com.careerplatform.career_assistant.enums.Plan.PRO
                   OR u.currentMonthYear IS NULL
                   OR u.currentMonthYear <> :month
                   OR u.resumesAnalyzedThisMonth < :limit)
            """)
    int tryConsumeResumeQuota(@Param("userId") Long userId,
                              @Param("month") String month,
                              @Param("limit") int limit);

    /**
     * Refunds one roadmap generation after a failed AI call.
     * Floored at 0 in the WHERE clause, so a double refund cannot mint free quota.
     *
     * The month guard matters when a call spans midnight on the last day of the month:
     * the counter may already have been reset, and decrementing it would take quota
     * away from the new month.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE User u SET u.roadmapsGeneratedThisMonth = u.roadmapsGeneratedThisMonth - 1
            WHERE u.id = :userId AND u.currentMonthYear = :month
              AND u.roadmapsGeneratedThisMonth > 0
            """)
    int releaseRoadmapQuota(@Param("userId") Long userId, @Param("month") String month);

    /** Resume analysis equivalent of releaseRoadmapQuota. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE User u SET u.resumesAnalyzedThisMonth = u.resumesAnalyzedThisMonth - 1
            WHERE u.id = :userId AND u.currentMonthYear = :month
              AND u.resumesAnalyzedThisMonth > 0
            """)
    int releaseResumeQuota(@Param("userId") Long userId, @Param("month") String month);
}