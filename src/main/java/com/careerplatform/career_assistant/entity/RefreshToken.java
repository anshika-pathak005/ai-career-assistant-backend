package com.careerplatform.career_assistant.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // hashed refresh token stored in DB — never store raw token
    @Column(nullable = false, unique = true)
    private String tokenHash;

    // which user this token belongs to
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // when this token expires (7 days from creation)
    @Column(nullable = false)
    private LocalDateTime expiryDate;

    // explicitly revoked — logout, password change, suspicious activity
    @Column(nullable = false)
    private boolean revoked = false;

    // which device/client created this token — useful for multi-device logout
    private String deviceInfo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // convenience method — token is invalid if expired OR revoked
    public boolean isValid() {
        return !revoked && LocalDateTime.now().isBefore(expiryDate);
    }
}