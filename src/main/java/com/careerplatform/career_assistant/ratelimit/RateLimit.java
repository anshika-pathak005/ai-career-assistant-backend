package com.careerplatform.career_assistant.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which rate limit bucket a controller method draws from, and how much.
 * Read by RateLimitInterceptor, which applies the limit per authenticated user.
 * An unannotated endpoint is not limited per-user; it still passes through the
 * IP-keyed GENERAL bucket in RateLimitingFilter.
 *
 * <pre>
 * &#64;RateLimit(tier = RateLimitTier.AI, cost = 7)
 * &#64;PostMapping("/generate")
 * public GenerateRoadmapResponse generate(...) { ... }
 * </pre>
 *
 * Declare the tier on the endpoint rather than matching URLs somewhere central, so a
 * new endpoint cannot silently land in the wrong bucket.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    RateLimitTier tier() default RateLimitTier.GENERAL;

    /**
     * Token units this endpoint draws from the tier's budget.
     * For AI endpoints set this in proportion to Groq token spend, roughly
     * max_tokens / 1000 — roadmap generation (7000) is 7, resume analysis (2500) is 3.
     */
    int cost() default 1;
}
