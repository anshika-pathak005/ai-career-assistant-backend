package com.careerplatform.career_assistant.ratelimit;

import com.careerplatform.career_assistant.observability.AppMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies per-user rate limiting to controller methods that use @RateLimit.
 *
 * It runs after authentication, so it can identify the current user.
 * If the user has exceeded the limit, the request is rejected with 429.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final RateLimitResponder responder;
    private final AppMetrics metrics;

    /**
     * Checks the rate limit before the controller method runs.
     *
     * It finds the @RateLimit annotation, gets the current user,
     * and asks RateLimiter whether the request can continue.
     *
     * Returns true when the request is allowed.
     * Returns false when the request is rejected.
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;   // static resources, error dispatches
        }

        RateLimit limit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (limit == null) {
            return true;
        }

        String userId = currentUserId();
        if (userId == null) {
            // Anonymous requests are covered by the IP-keyed filter. Keying them all
            // as one user here would put every unauthenticated caller in a shared
            // bucket, which is a denial-of-service primitive rather than a protection.
            return true;
        }

        RateLimiter.Decision decision = rateLimiter.tryConsume(limit.tier(), userId, limit.cost());

        if (!decision.allowed()) {
            log.warn("Rate limit hit for userId {} on {} (tier {}, cost {})",
                    userId, request.getRequestURI(), limit.tier(), limit.cost());
            // 7.2: layer "user" - see AppMetrics.rateLimitRejected for why the two
            // layers are counted separately rather than summed.
            metrics.rateLimitRejected(limit.tier().name(), "user");
            responder.writeTooManyRequests(response, decision);
            return false;   // stop — the controller is never reached
        }

        responder.applyHeaders(response, decision);
        return true;
    }

    /**
     * Gets the current authenticated user's ID from Spring Security.
     *
     * Returns null when there is no authenticated user.
     * Anonymous users are handled by the IP-based rate-limit filter.
     */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        return "anonymousUser".equals(name) ? null : name;
    }
}
