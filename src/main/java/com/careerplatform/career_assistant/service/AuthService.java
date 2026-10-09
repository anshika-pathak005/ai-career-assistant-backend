package com.careerplatform.career_assistant.service;

import com.careerplatform.career_assistant.dto.request.LoginRequest;
import com.careerplatform.career_assistant.dto.request.RegisterRequest;
import com.careerplatform.career_assistant.dto.request.RegisterStep2Request;
import com.careerplatform.career_assistant.dto.response.AuthResult;
import com.careerplatform.career_assistant.dto.response.LoginResponse;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.DuplicateResourceException;
import com.careerplatform.career_assistant.exception.InvalidCredentialsException;
import com.careerplatform.career_assistant.exception.ResourceNotFoundException;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.careerplatform.career_assistant.util.JwtUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;

    /**
     * A real BCrypt hash of a random string, compared against when an email is unknown
     * so both login paths cost the same. See login().
     *
     * Generated at startup rather than hardcoded so it always matches the configured
     * cost factor — a hash at the wrong cost, or a malformed one, would be rejected in
     * microseconds and silently reopen the timing gap it exists to close.
     */
    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        byte[] filler = new byte[24];
        new SecureRandom().nextBytes(filler);
        this.dummyHash = passwordEncoder.encode(Base64.getEncoder().encodeToString(filler));
    }

    /**
     * Step 1 registration — saves basic user info, hashes password, sets wizardStep = 1.
     * Returns tokens so the user is logged in immediately.
     *
     * Deliberately not @Transactional: BCrypt is ~100ms of CPU by design, and wrapping
     * this method would hold a pooled JDBC connection for all of it.
     */
    public AuthResult registerStep1(RegisterRequest request, String deviceInfo) {

        String normalizedEmail = request.getEmail().trim().toLowerCase();
        String normalizedName = normalizeName(request.getName());

        // throw 409 if email already taken — caught by GlobalExceptionHandler
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.warn("Registration failed - email already registered");
            throw new DuplicateResourceException("Email already registered");
        }

        User user = User.builder()
                .name(normalizedName)
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build();
        // 5.2: wizardStep, isProfileComplete, plan, roadmapsGeneratedThisMonth,
        // resumesAnalyzedThisMonth and currentMonthYear used to be set here by hand
        // because @Builder discarded their field initialisers. They now carry
        // @Builder.Default, so the entity supplies them and the next call site that
        // forgets one no longer writes null into a nullable = false column.

        User savedUser = userRepository.save(user);
        log.info("User registered successfully with id: {}", savedUser.getId());

        return issueTokens(savedUser, deviceInfo);
    }

    // Step 2 registration — updates remaining profile fields, marks profile complete
    @Transactional
    public User updateStep2(Long userId, RegisterStep2Request request) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        user.setUserCategory(request.getUserCategory());
        user.setDreamJob(request.getDreamJob());
        user.setIndustries(request.getIndustries());
        user.setSkills(request.getSkills());
        user.setExperienceYears(request.getExperienceYears());
        user.setCurrentRole(request.getCurrentRole());
        user.setWizardStep(2);
        user.setIsProfileComplete(true);

        User updatedUser = userRepository.save(user);
        log.info("Step 2 completed for userId: {}, profile marked complete", userId);

        return updatedUser;
    }

    /**
     * Validates credentials and returns an access token plus a refresh token.
     * The controller puts the access token in the body and the refresh token in a cookie.
     *
     * Failed attempts are counted against email + client IP, so a lockout only affects
     * the machine doing the guessing. clientIp is passed in rather than read from a
     * static holder so this stays testable without a servlet request.
     *
     * Not @Transactional, for the same reason as registerStep1: the BCrypt comparison
     * must not run while holding a pooled connection.
     */
    public AuthResult login(LoginRequest request, String deviceInfo, String clientIp) {

        String normalizedEmail = request.getEmail().trim().toLowerCase();
        String attemptKey = LoginAttemptService.key(normalizedEmail, clientIp);

        if (loginAttemptService.isBlocked(attemptKey)) {
            log.warn("Login blocked - too many failures from this client");
            throw new InvalidCredentialsException(
                    "Too many failed attempts. Please try again in 15 minutes.");
        }

        Optional<User> maybeUser = userRepository.findByEmail(normalizedEmail);

        // Always run exactly one BCrypt comparison, whether or not the user exists, so
        // the two paths take the same time and an attacker cannot discover which emails
        // are registered. Do not skip the dummy comparison when maybeUser is empty.
        boolean passwordMatches = maybeUser
                .map(user -> passwordEncoder.matches(request.getPassword(), user.getPasswordHash()))
                .orElseGet(() -> {
                    passwordEncoder.matches(request.getPassword(), dummyHash);
                    return false;
                });

        if (!passwordMatches) {
            loginAttemptService.recordFailure(attemptKey);
            // One message for both causes — saying "no such account" here would undo the
            // timing protection above in plain text.
            throw new InvalidCredentialsException("Invalid email or password");
        }

        User user = maybeUser.orElseThrow();
        loginAttemptService.recordSuccess(attemptKey);
        log.info("Login successful for userId: {}", user.getId());

        return issueTokens(user, deviceInfo);
    }

    /**
     * Rotates the refresh token and issues a new access token.
     * The raw refresh token comes from the cookie; the controller extracts and passes it.
     */
    @Transactional
    public AuthResult refresh(String rawRefreshToken, String deviceInfo) {

        RefreshTokenService.RotationResult rotation =
                refreshTokenService.rotateRefreshToken(rawRefreshToken, deviceInfo);

        User user = rotation.user();
        String newAccessToken = jwtUtil.generateToken(user.getId(), currentTokenVersion(user));

        log.info("Tokens refreshed for userId: {}", user.getId());

        return new AuthResult(loginResponse(newAccessToken, user), rotation.rawToken());
    }

    /**
     * Ends the user's session on every device.
     * Bumping tokenVersion invalidates every access token they hold; revoking the
     * refresh tokens stops new ones being minted.
     *
     * Always completes without throwing, and the endpoint always returns 204. Requiring
     * a valid token would mean a user with an expired one could never clear their
     * session, and a failure response would reveal whether the token was still valid.
     *
     * The access token is validated before its userId is trusted; if it is missing or
     * expired the refresh cookie is used instead.
     */
    @Transactional
    public void logout(String accessToken, String rawRefreshToken) {

        Long userId = jwtUtil.tryParse(accessToken)
                .map(jwtUtil::userIdFrom)
                .orElseGet(() -> refreshTokenService.findUserIdOrNull(rawRefreshToken));

        if (userId == null) {
            log.info("Logout called without a usable token - nothing to revoke");
            return;
        }

        Optional<User> maybeUser = userRepository.findById(userId);
        if (maybeUser.isEmpty()) {
            log.warn("Logout for unknown userId: {}", userId);
            return;
        }

        // Refresh tokens first, while the entity is still managed. They are revoked
        // rather than deleted, because the rows are what make reuse detection work.
        refreshTokenService.revokeAllTokens(maybeUser.get());

        // Then invalidate the access tokens. This runs as an UPDATE by id with
        // clearAutomatically, so no stale in-memory copy of the row can be flushed
        // afterwards and quietly undo it.
        userRepository.bumpTokenVersion(userId);

        log.info("Logout successful for userId: {}", userId);
    }

    // ---------------------------------------------------------------- helpers

    /** Issues an access and refresh token pair and packages the login response. */
    private AuthResult issueTokens(User user, String deviceInfo) {
        String accessToken = jwtUtil.generateToken(user.getId(), currentTokenVersion(user));
        String refreshToken = refreshTokenService.generateRefreshToken(user, deviceInfo);
        return new AuthResult(loginResponse(accessToken, user), refreshToken);
    }

    private LoginResponse loginResponse(String accessToken, User user) {
        return new LoginResponse(
                accessToken,
                new LoginResponse.UserInfo(
                        user.getId(),
                        user.getEmail(),
                        user.getName(),
                        user.getWizardStep(),
                        user.getIsProfileComplete()
                )
        );
    }

    /** Null-safe read of the user's token version. */
    private int currentTokenVersion(User user) {
        return user.getTokenVersion() != null ? user.getTokenVersion() : 0;
    }

    // capitalizes first letter of each word - "john doe" -> "John Doe"
    private String normalizeName(String name) {
        if (name == null || name.isBlank()) return name;

        String[] words = name.trim().split("\\s+");

        StringBuilder normalized = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                normalized.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase())
                        .append(" ");
            }
        }

        return normalized.toString().trim();
    }
}
