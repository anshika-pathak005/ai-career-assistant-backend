package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.request.UpdateProfileRequest;
import com.careerplatform.career_assistant.dto.response.GenerationLimitResponse;
import com.careerplatform.career_assistant.dto.response.UserProfileResponse;
import com.careerplatform.career_assistant.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * GET /api/user/profile
     * Returns complete profile of currently logged in user.
     * Frontend uses this to pre-fill the roadmap generation form.
     * userId extracted from JWT — no need to pass in request.
     */
    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile() {
        log.info("Profile fetch request received");
        return ResponseEntity.ok(userService.getUserProfile());
    }

    /**
     * PUT /api/user/profile
     * General profile update — called from profile settings page.
     * Partial update — only fields that are non-null get updated.
     * Does NOT trigger roadmap generation — just saves profile data.
     */
    @PutMapping("/profile")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @RequestBody UpdateProfileRequest request) {
        log.info("Profile update request received");
        return ResponseEntity.ok(userService.updateProfile(request));
    }

    /**
     * GET /api/user/usage
     * Returns current roadmap generation usage for logged in user.
     * Frontend calls this on dashboard load to show usage indicator.
     * e.g. "2/3 roadmaps used this month"
     */
    @GetMapping("/roadmap-usage")
    public ResponseEntity<GenerationLimitResponse> getUsage() {
        log.info("Usage info request received");
        return ResponseEntity.ok(userService.getUsage());
    }
}