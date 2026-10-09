package com.careerplatform.career_assistant.observability;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Reports how the Groq provider has been behaving, from the traffic we already sent
 * (7.2).
 *
 * Registered as the {@code groq} indicator, so it appears at
 * {@code /actuator/health} as {@code components.groq}.
 *
 * <h2>Passive on purpose</h2>
 *
 * The obvious implementation is a probe request to Groq on every scrape. That would:
 * bill us for a completion every fifteen seconds, consume provider-side rate limit that
 * belongs to users, add a network call to an endpoint that must answer fast, and - worst
 * - report on a synthetic request rather than on the requests users are actually making.
 * The prompts differ, the token counts differ, and the failure modes differ with them.
 * This indicator reads {@link AppMetrics} instead, which is already recording the real
 * calls.
 *
 * The cost of that choice, stated plainly: with no traffic there is nothing to report,
 * so an outage that starts at 3am is invisible until the first user hits it. That is the
 * right trade for a dependency we cannot fix anyway.
 *
 * <h2>DEGRADED, not DOWN</h2>
 *
 * Groq being unreachable is not a reason to restart this process or to take it out of
 * the load balancer - the rest of the app works, and a restart cannot fix somebody
 * else's outage. If this indicator returned DOWN and the readiness group included it,
 * every instance would go unready at once during a provider incident and the app would
 * be entirely unavailable instead of partly degraded.
 *
 * So it returns a custom DEGRADED status, mapped to HTTP 200 in
 * application.properties, and the liveness and readiness groups name their members
 * explicitly rather than including everything. The alert rule in
 * {@code ops/prometheus/alerts.yml} is what pages someone.
 */
@Component("groq")
@RequiredArgsConstructor
public class GroqHealthIndicator implements HealthIndicator {

    /** Custom status. Must be listed in management.endpoint.health.status.order. */
    public static final Status DEGRADED = new Status("DEGRADED");

    /**
     * Consecutive failures before the indicator degrades.
     *
     * Three, because GroqRetryInterceptor already retries each call up to three times:
     * three failures here means nine attempts, which is not a blip.
     */
    private static final int FAILURE_THRESHOLD = 3;

    /**
     * How long a failure streak keeps the indicator degraded with no further traffic.
     *
     * Without this, a burst of failures at midnight would still show DEGRADED at noon
     * even though the provider recovered and nobody has called since - a health endpoint
     * reporting on a state that is no longer true.
     */
    private static final Duration STALENESS_WINDOW = Duration.ofMinutes(10);

    private final AppMetrics metrics;

    @Override
    public Health health() {
        AppMetrics.AiHealth snapshot = metrics.aiHealth();

        Health.Builder builder = Health.status(statusFor(snapshot))
                .withDetail("consecutiveFailures", snapshot.consecutiveFailures());

        if (snapshot.lastSuccess() != null) {
            builder.withDetail("lastSuccess", snapshot.lastSuccess().toString());
        }
        if (snapshot.lastFailure() != null) {
            builder.withDetail("lastFailure", snapshot.lastFailure().toString())
                   .withDetail("lastFailureOutcome", snapshot.lastFailureOutcome());
        }
        if (snapshot.lastSuccess() == null && snapshot.lastFailure() == null) {
            builder.withDetail("note", "no calls observed since startup");
        }

        return builder.build();
    }

    private Status statusFor(AppMetrics.AiHealth snapshot) {
        if (snapshot.consecutiveFailures() < FAILURE_THRESHOLD) {
            return Status.UP;
        }
        Instant lastFailure = snapshot.lastFailure();
        boolean stale = lastFailure == null
                || lastFailure.isBefore(Instant.now().minus(STALENESS_WINDOW));
        return stale ? Status.UP : DEGRADED;
    }
}
