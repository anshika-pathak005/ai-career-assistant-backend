package com.careerplatform.career_assistant.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The two Groq accounts this app talks to, bound from {@code groq.api.*} and
 * {@code groq.resume.api.*}.
 *
 * They are separate accounts on purpose: resume analysis and roadmap generation have
 * independent provider-side rate limits, so exhausting one does not take the other down.
 *
 * 6.2 uses this type as the argument to GroqClient, which is what lets a single
 * transport-only client serve both callers without qualifiers or duplicated beans.
 */
@Validated
@ConfigurationProperties(prefix = "groq")
public record GroqProperties(

        @NotNull(message = "groq.api.* is not configured")
        @Valid Endpoint api,

        @NotNull(message = "groq.resume.api.* is not configured")
        @Valid Resume resume) {

    /** Wrapper for the nested {@code groq.resume.api.*} keys. */
    public record Resume(@NotNull @Valid Endpoint api) {}

    /** One provider account: where to send, what key to send, which model to ask for. */
    public record Endpoint(
            @NotBlank(message = "API key is missing") String key,
            @NotBlank(message = "API url is missing") String url,
            @NotBlank(message = "model is missing") String model) {
    }

    /** Shorthand for the resume account's endpoint. */
    public Endpoint resumeApi() {
        return resume.api();
    }
}
