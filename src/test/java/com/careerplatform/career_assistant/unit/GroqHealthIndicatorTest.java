package com.careerplatform.career_assistant.unit;

import com.careerplatform.career_assistant.observability.AppMetrics;
import com.careerplatform.career_assistant.observability.GroqHealthIndicator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan item 7.2 — the AI provider health indicator.
 *
 * The behaviour worth protecting here is a single line: this returns DEGRADED, never
 * DOWN. If a future change "simplifies" it to DOWN and the readiness group picks it up,
 * a Groq outage takes every instance out of the load balancer at once — converting
 * "roadmap generation is broken" into "nobody can log in". That is a five-character edit
 * with an outage attached, so it gets an assertion.
 *
 * SimpleMeterRegistry rather than a mock: AppMetrics is doing real work with real meters,
 * and mocking it would test the mock.
 */
@DisplayName("7.2 — Groq health degrades, but never reports DOWN")
class GroqHealthIndicatorTest {

    private AppMetrics metrics;
    private GroqHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        metrics = new AppMetrics(new SimpleMeterRegistry());
        indicator = new GroqHealthIndicator(metrics);
    }

    private void recordCall(String outcome) {
        metrics.stopAiCall(metrics.startAiCall(), "roadmap", outcome);
    }

    @Test
    @DisplayName("With no traffic it reports UP and says so")
    void noTrafficIsUp() {
        Health health = indicator.health();

        // Not UNKNOWN and not DOWN. Having sent no requests is not evidence of a problem,
        // and a health endpoint that alarms on an idle service trains people to ignore it.
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("note");
    }

    @Test
    @DisplayName("One or two failures are a blip, not a degradation")
    void isolatedFailuresDoNotDegrade() {
        recordCall("provider_error");
        recordCall("provider_error");

        // The threshold is 3 because GroqRetryInterceptor already retries each call three
        // times — two failures here is six attempts, which is still plausibly transient.
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("Three consecutive failures degrade — and it is DEGRADED, not DOWN")
    void sustainedFailuresDegrade() {
        recordCall("provider_error");
        recordCall("provider_error");
        recordCall("connection_error");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(GroqHealthIndicator.DEGRADED);
        assertThat(health.getStatus())
                .as("DOWN here would take every pod out of the load balancer during a "
                        + "third-party outage — see the class Javadoc before changing this")
                .isNotEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("consecutiveFailures", 3);
    }

    @Test
    @DisplayName("A success clears the streak")
    void successResetsTheStreak() {
        recordCall("provider_error");
        recordCall("provider_error");
        recordCall("provider_error");
        assertThat(indicator.health().getStatus()).isEqualTo(GroqHealthIndicator.DEGRADED);

        recordCall("success");

        // Recovery has to be automatic. An indicator that latches until a restart is one
        // people learn to disregard.
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("A 200 with an unusable body counts as a failure, not a success")
    void unreadableResponseIsAFailure() {
        recordCall("unreadable_response");
        recordCall("unreadable_response");
        recordCall("unreadable_response");

        // Empty choices array, blank content, unparseable envelope. The status line said
        // 200 and every generation still failed. Tagging these "success" is how a
        // dashboard shows a healthy provider during a total outage.
        assertThat(indicator.health().getStatus()).isEqualTo(GroqHealthIndicator.DEGRADED);
    }
}
