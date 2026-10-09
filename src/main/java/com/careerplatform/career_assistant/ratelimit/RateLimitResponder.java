package com.careerplatform.career_assistant.ratelimit;

import com.careerplatform.career_assistant.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes rate limit headers and the 429 response body.
 * Headers go on every response, not only rejections, so a client can show remaining
 * quota without a separate endpoint.
 * Shared by RateLimitingFilter and RateLimitInterceptor so the two cannot drift into
 * emitting different header names or a different error shape.
 */
@Component
@RequiredArgsConstructor
public class RateLimitResponder {

    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RESET = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private final ObjectMapper objectMapper;

    /** Applied to allowed requests so the client can see its remaining budget. */
    public void applyHeaders(HttpServletResponse response, RateLimiter.Decision decision) {
        response.setHeader(HEADER_LIMIT, String.valueOf(decision.limit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(decision.remaining()));
        // Reset is seconds until the full budget returns; Retry-After below is seconds
        // until this one request would succeed.
        response.setHeader(HEADER_RESET, String.valueOf(decision.resetSeconds()));
    }

    /**
     * Rejects the request with 429, the rate limit headers and a Retry-After.
     * Retry-After carries a real number so a retrying client waits instead of
     * hammering, which would turn one rejection into a loop of them.
     */
    public void writeTooManyRequests(HttpServletResponse response,
                                     RateLimiter.Decision decision) throws IOException {
        applyHeaders(response, decision);
        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(429,
                "Too many requests. Please try again in "
                        + decision.retryAfterSeconds() + " seconds."));
    }

}
