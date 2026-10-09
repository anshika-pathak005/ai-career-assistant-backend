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
import com.careerplatform.career_assistant.security.CurrentUserId;
import com.careerplatform.career_assistant.service.AuthService;
import com.careerplatform.career_assistant.util.ClientIpResolver;
import com.careerplatform.career_assistant.util.CookieUtil;
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
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CookieUtil cookieUtil;

    // Resolves the caller's IP for the login lockout key. Shared with the rate limiter
    // so the two cannot disagree about who a caller is.
    private final ClientIpResolver clientIpResolver;

    // POST /api/v1/auth/register/step1
    // Public endpoint — no auth required
    // @Valid triggers validation on RegisterRequest fields before hitting service
    @PostMapping("/register/step1")
    public ResponseEntity<LoginResponse> registerStep1(
            @Valid @RequestBody RegisterRequest request,
            @RequestHeader(value = "User-Agent", defaultValue = "unknown") String deviceInfo,
            HttpServletResponse response) {

        /*
         * 6.7: the email used to be logged here. An email in the application log means
         * the log aggregator is in scope for data protection and a leaked log file is a
         * user list. The userId is logged instead, once the row exists - see AuthService.
         */
        log.info("Step 1 registration request received");

        // Register user and generate both access token and refresh token
        AuthResult result = authService.registerStep1(request, deviceInfo);

        // Set refresh token as HTTP-only cookie for automatic browser storage
        cookieUtil.setRefreshTokenCookie(response, result.getRawRefreshToken());

        // Return access token in response body for client to use in subsequent requests
        return ResponseEntity.status(HttpStatus.CREATED).body(result.getLoginResponse());
    }

    // PUT /api/v1/auth/register/step2
    // Updates remaining profile fields after Step 1, marks profile complete
    // userId passed as query param — JWT will handle this automatically
    @PutMapping("/register/step2")
    public ResponseEntity<RegisterStep2Response> registerStep2(
            // Identity comes from the SecurityContext, which JwtAuthenticationFilter
            // fills only after signature, expiry and revocation checks have passed.
            // 6.1: @CurrentUserId rather than @AuthenticationPrincipal, so the whole app
            // has one way of answering "who is calling".
            @CurrentUserId Long userId,
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
                        user.getIsProfileComplete(),
                        user.getGoalType(),
                        user.getProjectLevel(),
                        user.getWorkPreference()
                )
        );
    }

    // POST /api/v1/auth/login
    // accessToken → response body
    // refreshToken → httpOnly cookie (browser stores, JS cannot access)
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "User-Agent", defaultValue = "unknown") String deviceInfo,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        // The email is not logged: it is the identifier an attacker is trying to
        // confirm, and access logs are a lower-trust store than the database.
        log.info("Login request received");
        AuthResult result = authService.login(request, deviceInfo, clientIpResolver.resolve(httpRequest));

        // set refreshToken as httpOnly cookie
        cookieUtil.setRefreshTokenCookie(response, result.getRawRefreshToken());

        // send accessToken + user info in body
        return ResponseEntity.ok(result.getLoginResponse());
    }

    // POST /api/v1/auth/refresh
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

    // POST /api/v1/auth/logout
    // deletes refresh tokens from DB + blacklists access token + clears cookie from browser
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Logout is public and always returns 204. Requiring a valid token would mean a
        // user with an expired one could never clear their session, and a failure
        // response would leak whether the token was still valid.
        String accessToken = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7)
                : null;
        String rawRefreshToken = cookieUtil.getRefreshTokenFromCookie(request).orElse(null);

        authService.logout(accessToken, rawRefreshToken);

        // clear cookie from browser
        cookieUtil.clearRefreshTokenCookie(response);

        return ResponseEntity.noContent().build(); // 204
    }
}