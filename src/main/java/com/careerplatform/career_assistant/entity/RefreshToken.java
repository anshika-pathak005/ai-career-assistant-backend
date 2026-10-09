package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One refresh token, stored as a hash.
 * Rotation never deletes rows — it marks them revoked and links them to their successor,
 * which is what makes token reuse detectable.
 */
// @Data removed (5.1). Its equals() read the lazy `user` field, so comparing two
// RefreshTokens could fire a query or throw LazyInitializationException.
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_family", columnList = "family_id"),
        @Index(name = "idx_refresh_user", columnList = "user_id"),
        @Index(name = "idx_refresh_expiry", columnList = "expiry_date")
})
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // hashed refresh token stored in DB - never store raw token
    @Column(nullable = false, unique = true)
    @ToString.Exclude   // the credential itself, hashed or not
    private String tokenHash;

    // which user this token belongs to
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    /**
     * Groups every token in one rotation chain, from a single login until the chain is
     * broken. Presenting a token whose row is already revoked means someone holds a copy
     * of a token the legitimate client has moved on from, and the whole family is burned.
     *
     * Nullable at the DB level on purpose: ddl-auto=update has to add this column to a
     * table that already has rows, and a NOT NULL add with no default is the kind of
     * ALTER that fails at deploy time. Rows written by this code always set it; older
     * rows are adopted into a fresh family by RefreshTokenService.rotateRefreshToken.
     */
    @Column(name = "family_id", length = 36)
    private String familyId;

    // when this token expires (7 days from creation)
    @Column(name = "expiry_date", nullable = false)
    private LocalDateTime expiryDate;

    // explicitly revoked - rotated away, logged out, password changed, family burned
    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * Hash of the token that replaced this one during rotation.
     * Audit trail only — it lets you walk a chain forwards when investigating a theft.
     */
    @Column(name = "replaced_by_hash")
    @ToString.Exclude
    private String replacedByHash;

    // which device/client created this token - useful for multi-device logout
    private String deviceInfo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiryDate);
    }

    // convenience method - token is invalid if expired OR revoked
    public boolean isValid() {
        return !revoked && !isExpired();
    }


    /** Identity-based equals/hashCode — see the note on User. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshToken other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    /** Marks this token as rotated away, pointing at its successor. */
    public void revokeReplacedBy(String successorHash) {
        this.revoked = true;
        this.revokedAt = LocalDateTime.now();
        this.replacedByHash = successorHash;
    }
}
