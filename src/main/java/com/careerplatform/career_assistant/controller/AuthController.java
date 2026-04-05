package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.request.RegisterRequest;
import com.careerplatform.career_assistant.dto.request.RegisterStep2Request;
import com.careerplatform.career_assistant.dto.response.RegisterResponse;
import com.careerplatform.career_assistant.dto.response.RegisterStep2Response;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // POST /api/auth/register/step1
    // Public endpoint — no auth required
    // @Valid triggers validation on RegisterRequest fields before hitting service
    @PostMapping("/register/step1")
    public ResponseEntity<RegisterResponse> registerStep1(@Valid @RequestBody RegisterRequest request) {
        log.info("Step 1 registration request received for email: {}", request.getEmail());

        User user = authService.registerStep1(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                new RegisterResponse(
                        "Account created successfully",
                        user.getId(),
                        user.getEmail(),
                        user.getName(),
                        user.getWizardStep()
                )
        );
    }

    // PUT /api/auth/register/step2
    // Updates remaining profile fields after Step 1, marks profile complete
    // userId passed as query param — JWT will handle this automatically
    @PutMapping("/register/step2")
    public ResponseEntity<RegisterStep2Response> registerStep2(
            @RequestParam Long userId,
            @Valid @RequestBody RegisterStep2Request request) {

        log.info("Step 2 registration request received for userId: {}", userId);

        User user = authService.updateStep2(userId, request);

        return ResponseEntity.ok(
                new RegisterStep2Response(
                        "Profile updated successfully",
                        user.getId(),
                        user.getUserCategory(),
                        user.getDreamJob(),
                        user.getWizardStep(),
                        user.getIsProfileComplete()
                )
        );
    }
}