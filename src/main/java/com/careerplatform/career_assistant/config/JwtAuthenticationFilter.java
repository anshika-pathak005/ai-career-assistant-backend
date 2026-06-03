package com.careerplatform.career_assistant.config;

import com.careerplatform.career_assistant.service.UserDetailsServiceImpl;
import com.careerplatform.career_assistant.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Runs once per request — intercepts every request before it hits the controller
// Checks for JWT token in Authorization header, validates it, sets authentication
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // skip JWT filter entirely for public endpoints
        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // get Authorization header from request
        // expected format: "Bearer eyJhbGciOiJIUzI1NiJ9..."
        String authHeader = request.getHeader("Authorization");

        // if no token present — skip filter, let request continue
        // public endpoints (login, register) will pass through here without token
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // extract token by removing "Bearer " prefix (7 characters)
        String token = authHeader.substring(7);
        log.info("JWT token found in request");

        try {
            // extract userId from token
            Long userId = jwtUtil.extractUserId(token);
            String userIdStr = String.valueOf(userId);

            // only authenticate if:
            // 1. userId was extracted successfully
            // 2. no authentication is already set in SecurityContext (avoid re-authentication)
            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // validate token — checks signature and expiry
                if (jwtUtil.validateToken(token)) {

                    // load user from DB using userId
                    UserDetails userDetails = userDetailsService.loadUserByUsername(userIdStr);

                    // create authentication token — this is what Spring Security understands
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,                        // credentials null — token already validated
                                    userDetails.getAuthorities() // empty for now, roles added later
                            );

                    // attach request details (IP, session) to authentication
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // set authentication in SecurityContext
                    // this is what tells Spring — "this request is authenticated, let it through"
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.info("Authentication set in SecurityContext for userId: {}", userId);

                } else {
                    log.warn("Invalid or expired JWT token for userId: {}", userId);
                    // return 401 immediately — then frontend refresh token logic triggers
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"status\": 401, \"message\": \"Token expired. Please refresh.\"}"
                    );
                    return; // stop filter chain
                }
            }

        } catch (Exception ex) {
            // token is malformed or tampered — log and continue without setting auth
            // Spring Security will block the request at SecurityFilterChain level
            log.error("JWT authentication failed: {}", ex.getMessage());
            // return 401 on malformed/tampered token too
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\": 401, \"message\": \"Invalid token. Please login again.\"}"
            );
            return;
        }

        // continue the filter chain — request proceeds to controller
        filterChain.doFilter(request, response);
    }
}