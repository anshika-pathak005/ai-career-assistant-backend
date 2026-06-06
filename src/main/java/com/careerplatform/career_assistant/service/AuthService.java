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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;


    // Step 1 registration — saves basic user info, hashes password, sets wizardStep = 1
    public AuthResult registerStep1(RegisterRequest request, String deviceInfo) {

        log.info("Registering new user with email: {}", request.getEmail());

        // normalize inputs before any processing
        String normalizedEmail = request.getEmail().trim().toLowerCase();
        String normalizedName = normalizeName(request.getName());

        // throw 409 if email already taken — caught by GlobalExceptionHandler
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.warn("Registration failed — email already registered: {}", request.getEmail());
            throw new DuplicateResourceException("Email already registered");
        }

        // Create a new User object using Builder pattern (avoids constructor order issues and improves readability)
        User user = User.builder()
                .name(normalizedName)
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .wizardStep(1)
                .isProfileComplete(false)
                .plan("FREE")
                .roadmapsGeneratedThisMonth(0)
                .resumesAnalyzedThisMonth(0)
                .currentMonthYear("")
                .build();

        User savedUser = userRepository.save(user);
        log.info("User registered successfully with id: {}", savedUser.getId());

        //generating tokens to send on frontend as user has just registered, and we want to log them in immediately
        String accessToken = jwtUtil.generateToken(savedUser.getId(), savedUser.getEmail());
        String refreshToken = refreshTokenService.generateRefreshToken(savedUser, deviceInfo);

        log.info("Tokens generated for new user: {}", savedUser.getId());

        // RETURN AuthResult instead of User (same pattern as login)
        return new AuthResult(
                new LoginResponse(
                        accessToken,
                        new LoginResponse.UserInfo(
                                savedUser.getId(),
                                savedUser.getEmail(),
                                savedUser.getName(),
                                savedUser.getWizardStep(),
                                savedUser.getIsProfileComplete()
                        )
                ),
                refreshToken
        );
    }

    // Step 2 registration — updates remaining profile fields, marks profile complete
    public User updateStep2(Long userId, RegisterStep2Request request) {

        log.info("Step 2 update started for userId: {}", userId);

        // find user — throws 404 if not found, caught by GlobalExceptionHandler
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


    // Login — validates credentials, returns JWT access and refresh token
    // Login — returns AuthResult instead of LoginResponse
    // reason: controller needs refreshToken separately to set as cookie
    public AuthResult login(LoginRequest request, String deviceInfo) {
        log.info("Login attempt for email: {}", request.getEmail());

        String normalizedEmail = request.getEmail().trim().toLowerCase();

        // find user by email — throw 401 if not found
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        // compare raw password with stored BCrypt hash
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Wrong password attempt for email: {}", request.getEmail());
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // generate both tokens
        String accessToken = jwtUtil.generateToken(user.getId(), user.getEmail());
        String refreshToken = refreshTokenService.generateRefreshToken(user, deviceInfo);
        log.info("Login successful for userId: {}", user.getId());

        // accessToken goes in body, refreshToken goes in cookie — controller handles that
        return new AuthResult(
                new LoginResponse(
                        accessToken,
                        new LoginResponse.UserInfo(
                                user.getId(),
                                user.getEmail(),
                                user.getName(),
                                user.getWizardStep(),
                                user.getIsProfileComplete()
                        )
                ),
                refreshToken
        );
    }

    // Refresh — reads rawRefreshToken from cookie (controller extracts and passes it)
    // rotates refresh token + generates new access token
    public AuthResult refresh(String rawRefreshToken, String deviceInfo) {
        log.info("Refresh token request received");

        Long userId = refreshTokenService.getUserIdFromToken(rawRefreshToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // rotate — delete old refresh token, generate new one
        String newRefreshToken = refreshTokenService.rotateRefreshToken(rawRefreshToken, deviceInfo);
        String newAccessToken = jwtUtil.generateToken(user.getId(), user.getEmail());
        log.info("Tokens refreshed for userId: {}", userId);

        return new AuthResult(
                new LoginResponse(
                        newAccessToken,
                        new LoginResponse.UserInfo(
                                user.getId(),
                                user.getEmail(),
                                user.getName(),
                                user.getWizardStep(),
                                user.getIsProfileComplete()
                        )
                ),
                newRefreshToken
        );
    }

    // Logout — deletes all refresh tokens from DB + controller clears cookie
    public void logout(Long userId) {
        log.info("Logout request for userId: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        refreshTokenService.deleteAllTokens(user);
        log.info("Logout successful for userId: {}", userId);
    }

//helpers
// capitalizes first letter of each word — "john doe" → "John Doe"
private String normalizeName(String name) {
    if (name == null || name.isBlank()) return name;

    String trimmed = name.trim();
    String[] words = trimmed.split("\\s+"); // split on multiple spaces

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

