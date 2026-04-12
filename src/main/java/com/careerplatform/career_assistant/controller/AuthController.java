package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.request.LoginRequest;
import com.careerplatform.career_assistant.dto.request.RegisterRequest;
import com.careerplatform.career_assistant.dto.request.RegisterStep2Request;
import com.careerplatform.career_assistant.dto.response.AuthResult;
import com.careerplatform.career_assistant.dto.response.LoginResponse;
import com.careerplatform.career_assistant.dto.response.RegisterResponse;
import com.careerplatform.career_assistant.dto.response.RegisterStep2Response;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.exception.TokenRevokedException;
import com.careerplatform.career_assistant.service.AuthService;
import com.careerplatform.career_assistant.util.CookieUtil;
import com.careerplatform.career_assistant.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final CookieUtil cookieUtil;
    private final JwtUtil jwtUtil;

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

    // POST /api/auth/login
    // accessToken → response body
    // refreshToken → httpOnly cookie (browser stores, JS cannot access)
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "User-Agent", defaultValue = "unknown") String deviceInfo,
            HttpServletResponse response) {

        log.info("Login request for email: {}", request.getEmail());
        AuthResult result = authService.login(request, deviceInfo);

        // set refreshToken as httpOnly cookie
        cookieUtil.setRefreshTokenCookie(response, result.getRawRefreshToken());

        // send accessToken + user info in body
        return ResponseEntity.ok(result.getLoginResponse());
    }

    // POST /api/auth/refresh
    // browser automatically sends refreshToken cookie — no body needed from frontend
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader(value = "User-Agent", defaultValue = "unknown") String deviceInfo) {

        // extract refreshToken from cookie — browser sent it automatically
        String rawRefreshToken = cookieUtil.getRefreshTokenFromCookie(request)
                .orElseThrow(() -> new TokenRevokedException("No refresh token found. Please login again."));

        AuthResult result = authService.refresh(rawRefreshToken, deviceInfo);

        // set new rotated refreshToken as cookie — old one deleted from DB
        cookieUtil.setRefreshTokenCookie(response, result.getRawRefreshToken());

        return ResponseEntity.ok(result.getLoginResponse());
    }

    // POST /api/auth/logout
    // deletes refresh tokens from DB + clears cookie from browser
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader("Authorization") String authHeader,
            HttpServletResponse response) {

        // extract userId from accessToken
        Long userId = jwtUtil.extractUserId(authHeader.substring(7));
        authService.logout(userId);

        // clear cookie from browser
        cookieUtil.clearRefreshTokenCookie(response);

        return ResponseEntity.noContent().build(); // 204
    }
}