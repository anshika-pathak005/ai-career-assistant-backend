package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import com.careerplatform.career_assistant.enums.Plan;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "users")
// @Data removed (5.1): it generated a toString() containing passwordHash, and
// equals/hashCode over every field. Explicit versions are below.
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    @ToString.Exclude   // one careless log.debug("{}", user) would print the bcrypt hash
    private String passwordHash;

    /**
     * Revocation counter. Every access token carries this value as its "tv" claim, and
     * JwtAuthenticationFilter rejects a token whose claim does not match.
     *
     * Bumped on logout and on password change, which instantly invalidates every access
     * token the user holds, on every instance, with no shared cache.
     *
     * columnDefinition carries DEFAULT 0 because ddl-auto=update adds this column to a
     * table that already has rows; without a default those rows would be NULL.
     */
    @Column(name = "token_version", nullable = false, columnDefinition = "INT NOT NULL DEFAULT 0")
    @Builder.Default
    private Integer tokenVersion = 0;

    /**
     * Authorisation role, surfaced as a GrantedAuthority by AuthenticatedUser.
     * Every user is ROLE_USER for now; @PreAuthorize checks read this.
     *
     * The column is named user_role, not role, because this table already has a
     * current_role column and ROLE / CURRENT_ROLE are keywords in MySQL 8.
     */
    @Column(name = "user_role", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'ROLE_USER'")
    @Builder.Default
    private String role = "ROLE_USER";

    @Column(nullable = false)
    private String name;

    @Column(length = 50)
    private String userCategory;

    @Column(name = "dream_job")
    private String dreamJob;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private List<String> industries;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private List<String> skills;

    @Column(name = "experience_years")
    private Integer experienceYears;

    @Column(name = "current_role")
    private String currentRole;

    @Column(name = "wizard_step", nullable = false)
    @Builder.Default
    private Integer wizardStep = 1;

    @Column(name = "is_profile_complete", nullable = false)
    @Builder.Default
    private Boolean isProfileComplete = false;

    @Column(name = "goal_type", length = 50)
    private String goalType;

    @Column(name = "project_level", length = 50)
    private String projectLevel;

    @Column(name = "work_preference", length = 50)
    private String workPreference;

    // skillLevels — {"React.js": "Beginner", "Java": "Intermediate"}
    @Column(columnDefinition = "json",name = "skill_levels")
    private String skillLevels;

    // weeklyHours — how many hours per week user can dedicate
    @Column(name = "weekly_hours")
    private Integer weeklyHours;

    // learningStyle — "Videos" / "Docs" / "Projects" / "Mixed"
    @Column(name = "learning_style")
    private String learningStyle;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * When this user asked for their account to be deleted, or null if they have not.
     *
     * 7.1: deletion used to happen inside the request that asked for it, behind nothing
     * but a password. Anyone who had got far enough to call the endpoint was already
     * holding that password, so it stopped nobody who mattered and left the real owner
     * with no recourse. The request now only sets this column; the data is destroyed by
     * UserSettingsService.purgeDueAccounts once app.account.deletion-grace-period has
     * elapsed, which gives the owner a window to notice and cancel.
     *
     * Nullable on purpose - "no pending deletion" is the absence of a timestamp rather
     * than a separate boolean, so the two cannot disagree.
     */
    @Column(name = "deletion_requested_at")
    private LocalDateTime deletionRequestedAt;

    // ── plan and generation tracking ──────────────────────────────────

    /**
     * 5.3: was a String compared with "PRO".equals(...). A typo compiled fine and
     * silently granted or denied PRO access. EnumType.STRING, never ORDINAL.
     *
     * 5.2: every field below carries @Builder.Default. Without it Lombok's builder
     * discards the initialiser and writes null into a nullable = false column. It only
     * worked because AuthService.registerStep1 happened to set all four by hand.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Plan plan = Plan.FREE;

    // how many roadmaps generated this month
    @Column(nullable = false)
    @Builder.Default
    private Integer roadmapsGeneratedThisMonth = 0;

    // add alongside roadmap generation tracking
    @Column(nullable = false)
    @Builder.Default
    private Integer resumesAnalyzedThisMonth = 0;

    // tracks which month the counter belongs to — "2026-05"
    // when this changes → counter resets automatically
    @Column(nullable = false)
    @Builder.Default
    private String currentMonthYear = "";

    /**
     * Identity-based equals/hashCode. @Data compared every field, which breaks for a
     * JPA entity: the hash changes when any field is set, so an entity put in a HashSet
     * before save can never be found after save, and comparing a Hibernate proxy against
     * a loaded instance returns false even for the same row.
     *
     * hashCode is a constant per class, which is the only value that stays stable across
     * a transient -> persistent transition.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}