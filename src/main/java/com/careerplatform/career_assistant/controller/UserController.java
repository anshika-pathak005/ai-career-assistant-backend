package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.request.UpdateProfileRequest;
import com.careerplatform.career_assistant.dto.response.GenerationLimitResponse;
import com.careerplatform.career_assistant.dto.response.UserProfileResponse;
import com.careerplatform.career_assistant.security.CurrentUserId;
import com.careerplatform.career_assistant.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 6.8: mapped at "/user", not "/api/user". WebMvcConfig prepends the version prefix to
 * every controller in this package, so the full path is /api/v1/user.
 */
@Slf4j
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * GET /api/v1/user/profile
     * Complete profile of the logged in user. Used to pre-fill the roadmap form.
     */
    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile(@CurrentUserId Long userId) {
        log.info("Profile fetch request for userId: {}", userId);
        return ResponseEntity.ok(userService.getUserProfile(userId));
    }

    /**
     * PUT /api/v1/user/profile
     * Partial update — only non-null fields are written.
     * Does NOT trigger roadmap generation.
     */
    @PutMapping("/profile")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @CurrentUserId Long userId,
            // 6.6: @Valid was missing, so the @Size caps added to UpdateProfileRequest
            // would have been decorative - unbounded strings straight into columns.
            @Valid @RequestBody UpdateProfileRequest request) {
        log.info("Profile update request for userId: {}", userId);
        return ResponseEntity.ok(userService.updateProfile(userId, request));
    }

    /**
     * GET /api/v1/user/roadmap-usage
     * Current roadmap generation usage, e.g. "2/3 used this month".
     */
    @GetMapping("/roadmap-usage")
    public ResponseEntity<GenerationLimitResponse> getUsage(@CurrentUserId Long userId) {
        log.info("Usage info request for userId: {}", userId);
        return ResponseEntity.ok(userService.getUsage(userId));
    }
}
