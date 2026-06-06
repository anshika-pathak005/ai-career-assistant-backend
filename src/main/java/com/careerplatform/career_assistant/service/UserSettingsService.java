package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.request.ChangePasswordRequest;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.InvalidCredentialsException;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.careerplatform.career_assistant.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Changes user password.
     * Validates current password first.
     * Validates new password matches confirm password.
     * Revokes all refresh tokens — forces re-login everywhere.
     */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // verify current password
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Current password is incorrect");
        }

        // verify new passwords match
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new InvalidCredentialsException("New passwords do not match");
        }

        // update password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // revoke all refresh tokens — user must re-login on all devices
        refreshTokenService.revokeAllTokens(user);
        log.info("Password changed and all sessions revoked for userId: {}", userId);
    }

    /**
     * Deletes user account permanently.
     * Validates password before deletion.
     * Cascades to all user data — roadmaps, resumes, analyses, tokens.
     */
    @Transactional
    public void deleteAccount(String password) {
        Long userId = getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // verify password before deletion
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Password is incorrect");
        }

        // delete refresh tokens first, then delete user
        refreshTokenRepository.deleteByUserId(userId);
        userRepository.delete(user);
        log.info("Account deleted for userId: {}", userId);
    }

    private Long getCurrentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext()
                .getAuthentication().getName());
    }
}