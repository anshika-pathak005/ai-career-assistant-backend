package com.careerplatform.career_assistant.repository;

import com.careerplatform.career_assistant.entity.RefreshToken;
import com.careerplatform.career_assistant.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // find token by hash — used during refresh and logout
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // delete all tokens for a user — used when logging out from all devices
    @Modifying
    @Transactional
    void deleteByUser(User user);

    // revoke all active tokens for a user — used on password change or suspicious activity
    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user = :user AND rt.revoked = false")
    void revokeAllUserTokens(User user);

    void deleteByUserId(Long userId);
}