package com.careerplatform.career_assistant.config;

import com.careerplatform.career_assistant.ratelimit.RateLimitResponder;
import com.careerplatform.career_assistant.ratelimit.RateLimitTier;
import com.careerplatform.career_assistant.ratelimit.RateLimiter;
import com.careerplatform.career_assistant.observability.AppMetrics;
import com.careerplatform.career_assistant.util.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate limits every request by client IP, before authentication runs.
 *
 * This layer exists to stop one host flooding the endpoints that have no authenticated
 * user yet — login and registration are the brute-force surface and must be protected
 * before anyone has proved who they are.
 *
 * Per-user limits, including the cost-weighted AI budget, are applied after
 * authentication by RateLimitInterceptor. An IP-keyed AI limit would be wrong in both
 * directions: a campus or office NAT shares one address between hundreds of users,
 * while one user with several addresses gets a budget for each.
 *
 * Edge-level flood protection belongs in front of the app, in Nginx or Cloudflare —
 * by the time a request reaches here a thread and a connection have already been spent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private final ClientIpResolver clientIpResolver;
    private final RateLimiter rateLimiter;
    private final RateLimitResponder responder;
    private final AppMetrics metrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String clientIp = clientIpResolver.resolve(request);
        RateLimitTier tier = tierFor(path);

        RateLimiter.Decision decision = rateLimiter.tryConsume(tier, clientIp, 1);

        if (!decision.allowed()) {
            log.warn("IP rate limit hit for {} on {} (tier {})", clientIp, path, tier);
            // 7.2: layer "ip". The client IP is NOT a tag - it is unbounded and it is
            // personal data. It stays in the log line, where it is greppable and subject
            // to log retention, rather than in a metrics backend that keeps it forever.
            metrics.rateLimitRejected(tier.name(), "ip");
            responder.writeTooManyRequests(response, decision);
            return;
        }

        responder.applyHeaders(response, decision);
        filterChain.doFilter(request, response);
    }

    /**
     * Picks the tier from whether the endpoint is reachable without a token.
     * Anything cost-related is declared on the endpoint itself with @RateLimit, so a
     * new path cannot be silently mis-bucketed by a prefix match here.
     */
    private RateLimitTier tierFor(String path) {
        return path.startsWith(ApiPaths.AUTH + "/") ? RateLimitTier.AUTH : RateLimitTier.GENERAL;
    }
}
