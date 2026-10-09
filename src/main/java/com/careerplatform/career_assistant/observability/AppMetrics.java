package com.careerplatform.career_assistant.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Every application-specific metric, in one place (7.2).
 *
 * <h2>Why a facade rather than injecting MeterRegistry everywhere</h2>
 *
 * Metric names and tag keys are a contract: dashboards, alert rules and recording rules
 * are all written against them. Spread across six services, a rename is a silent
 * breakage - the code still compiles, the alert just never fires again. Here, the names
 * exist once and the call sites read as domain verbs rather than as telemetry plumbing.
 *
 * <h2>Cardinality</h2>
 *
 * Every tag value below comes from an enum or a fixed string. None of them is a userId,
 * an IP, a path or an exception message. A metrics backend stores one time series per
 * distinct tag combination, so a single unbounded tag is how a Prometheus instance runs
 * out of memory - this is not a stylistic rule.
 *
 * <h2>AI health state</h2>
 *
 * This class also keeps a small rolling view of how the Groq calls are going, which
 * {@link GroqHealthIndicator} reads. It lives here because the recording happens here
 * anyway, and because the alternative - a health indicator that makes its own probe
 * request - would spend money and provider quota on every scrape, and would report on a
 * request nobody asked for rather than on the traffic users are actually sending.
 */
@Slf4j
@Component
public class AppMetrics {

    // ---- metric names. Changing one of these breaks a dashboard; do it deliberately.
    public static final String AI_CALL_TIMER = "careerassistant.ai.call";
    public static final String QUOTA_REJECTED = "careerassistant.quota.rejected";
    public static final String RATE_LIMIT_REJECTED = "careerassistant.ratelimit.rejected";
    public static final String UPLOAD_SIZE = "careerassistant.upload.size";
    public static final String EXTRACTION_TIMER = "careerassistant.resume.extraction";

    private final MeterRegistry registry;

    /** Bytes per accepted upload. Registered once: a summary is stateful. */
    private final DistributionSummary uploadSize;

    // ---- AI health state, read by GroqHealthIndicator
    private final AtomicInteger consecutiveAiFailures = new AtomicInteger();
    private final AtomicReference<Instant> lastAiSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> lastAiFailure = new AtomicReference<>();
    private final AtomicReference<String> lastAiFailureOutcome = new AtomicReference<>();

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.uploadSize = DistributionSummary.builder(UPLOAD_SIZE)
                .description("Size of accepted resume uploads")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // A gauge rather than a counter: the question is "is it failing right now",
        // not "how many times has it ever failed".
        registry.gauge("careerassistant.ai.consecutive_failures", consecutiveAiFailures);
    }

    // ------------------------------------------------------------------ AI calls

    /** Starts the clock on a Groq call. Pair with {@link #stopAiCall}. */
    public Timer.Sample startAiCall() {
        return Timer.start(registry);
    }

    /**
     * Records the duration and outcome of a Groq call.
     *
     * @param operation which call it was - "roadmap", "specializations", "resume".
     *                  Bounded by the three call sites.
     * @param outcome   "success", an HTTP status class such as "http_429", or
     *                  "connection_error". Never a raw exception message: those contain
     *                  URLs and ids and would be unbounded.
     */
    public void stopAiCall(Timer.Sample sample, String operation, String outcome) {
        sample.stop(Timer.builder(AI_CALL_TIMER)
                .description("Groq chat completion latency")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));

        if ("success".equals(outcome)) {
            consecutiveAiFailures.set(0);
            lastAiSuccess.set(Instant.now());
        } else {
            consecutiveAiFailures.incrementAndGet();
            lastAiFailure.set(Instant.now());
            lastAiFailureOutcome.set(outcome);
        }
    }

    /** Snapshot of how the AI provider has been behaving, for the health indicator. */
    public AiHealth aiHealth() {
        return new AiHealth(
                consecutiveAiFailures.get(),
                lastAiSuccess.get(),
                lastAiFailure.get(),
                lastAiFailureOutcome.get());
    }

    public record AiHealth(int consecutiveFailures,
                           Instant lastSuccess,
                           Instant lastFailure,
                           String lastFailureOutcome) {
    }

    // ------------------------------------------------------------------ quota

    /**
     * A generation refused because the user is out of monthly quota.
     *
     * Tagged by plan so "how many FREE users hit the ceiling this week" is answerable,
     * which is the number that decides whether the free limit is set sensibly.
     */
    public void quotaRejected(String plan, String type) {
        registry.counter(QUOTA_REJECTED, "plan", plan, "type", type).increment();
    }

    // ------------------------------------------------------------------ rate limits

    /**
     * A request refused with 429.
     *
     * @param tier  AUTH / AI / GENERAL
     * @param layer "ip" for the pre-auth filter, "user" for the post-auth interceptor.
     *              Worth separating: sustained rejections at the IP layer usually mean
     *              an attack, while rejections at the user layer usually mean the
     *              budget is set too low for legitimate use.
     */
    public void rateLimitRejected(String tier, String layer) {
        registry.counter(RATE_LIMIT_REJECTED, "tier", tier, "layer", layer).increment();
    }

    // ------------------------------------------------------------------ uploads

    public void uploadAccepted(long bytes) {
        uploadSize.record(bytes);
    }

    /**
     * Time spent in Tika.
     *
     * Separate from the AI timer because they fail differently and are fixed
     * differently: extraction is CPU on this box and bounded by the 20s timeout, while
     * an AI call is somebody else's availability.
     */
    public void extractionCompleted(Duration duration, String outcome) {
        Timer.builder(EXTRACTION_TIMER)
                .description("Resume text extraction duration")
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95)
                .register(registry)
                .record(duration);
    }
}
