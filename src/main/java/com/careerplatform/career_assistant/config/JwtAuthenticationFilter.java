package com.careerplatform.career_assistant.config;

import com.careerplatform.career_assistant.exception.ErrorResponse;
import com.careerplatform.career_assistant.security.AuthenticatedUser;
import com.careerplatform.career_assistant.service.UserDetailsServiceImpl;
import com.careerplatform.career_assistant.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Authenticates every request carrying a Bearer token, and populates the SecurityContext.
 *
 * Parses the token once, loads the user, then checks the token's "tv" claim against the
 * user's current tokenVersion. A mismatch means the token was revoked by a logout or a
 * password change, and the request is rejected with 401.
 *
 * Requests without a token are passed through untouched — Spring's authorization rules
 * stop them, and RestAuthenticationEntryPoint writes the 401.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Endpoints reachable without a token.
     * Exact match, not prefix match: an allowlist means any new endpoint under the auth
     * namespace is protected unless it is deliberately added here.
     * Keep in sync with the permitAll() list in SecurityConfig.
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            ApiPaths.REGISTER_STEP1,
            ApiPaths.LOGIN,
            ApiPaths.REFRESH,
            ApiPaths.LOGOUT
    );

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (PUBLIC_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        // No token: let the chain continue. The request is stopped by
        // authorizeHttpRequests and answered as JSON by RestAuthenticationEntryPoint.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        // Already authenticated earlier in the chain — do not redo it.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        Claims claims;
        try {
            // Signature, expiry, issuer and audience, all in one parse.
            claims = jwtUtil.parse(token);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected token on {}: {}", request.getRequestURI(), ex.getMessage());
            writeError(response, "Invalid or expired token. Please refresh or log in again.");
            return;
        }

        Long userId = jwtUtil.userIdFrom(claims);

        AuthenticatedUser user;
        try {
            user = userDetailsService.loadAuthenticatedUser(userId);
        } catch (UsernameNotFoundException ex) {
            // A correctly signed token for an account that no longer exists.
            log.warn("Valid token for unknown userId: {}", userId);
            writeError(response, "Invalid or expired token. Please refresh or log in again.");
            return;
        }

        // Revocation check. A mismatch means a logout or password change happened after
        // this token was issued.
        if (jwtUtil.tokenVersionFrom(claims) != user.getTokenVersion()) {
            log.info("Revoked token presented for userId: {}", userId);
            writeError(response, "Session ended. Please log in again.");
            return;
        }

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);

        filterChain.doFilter(request, response);
    }

    /**
     * Writes a 401 with the app's ErrorResponse shape.
     * The message is the same for every rejection reason on purpose: distinguishing
     * expired from tampered from unknown-user tells an attacker which part of a forged
     * token to change next.
     */
    private void writeError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(HttpStatus.UNAUTHORIZED.value(), message));
    }
}
