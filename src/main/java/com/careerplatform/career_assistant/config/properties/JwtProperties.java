package com.careerplatform.career_assistant.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Everything under the {@code jwt.} prefix, bound once and validated at startup.
 *
 * 6.4: these were five {@code @Value} fields spread across JwtUtil, CookieUtil and
 * RefreshTokenService. Three consequences of that, all fixed here:
 *
 *   1. A missing property failed during bean creation with an unhelpful stack trace.
 *      Now {@code @Validated} names the offending property in the failure message.
 *   2. {@code jwt.refresh-expiration-days} was declared in two separate classes with
 *      two separate defaults. They could drift; a record cannot.
 *   3. Field injection meant non-final fields and no way to construct the class in a
 *      unit test without standing up a Spring context.
 *
 * {@code @Size(min = 32)} is the HS256 key-length requirement expressed as configuration
 * rather than as a runtime check. Note it counts CHARACTERS while HS256 needs 256 BITS,
 * so JwtUtil still does the exact byte-length check - 32 characters of ASCII is 32 bytes,
 * but this constraint alone would not catch every encoding.
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(

        @NotBlank(message = "jwt.secret must be set - generate one with: openssl rand -base64 48")
        @Size(min = 32, message = "jwt.secret must be at least 32 characters for HS256")
        String secret,

        /*
         * @DurationUnit is load-bearing. Existing configuration says
         * "jwt.expiration=3600000" meaning milliseconds. Without this annotation a bare
         * number binds as milliseconds by Spring's default anyway, but relying on an
         * unstated default for a value that controls session length is not worth the
         * saving. Suffixed values such as "1h" or "30m" also work.
         */
        @NotNull(message = "jwt.expiration must be set")
        @DurationUnit(ChronoUnit.MILLIS)
        Duration expiration,

        @Positive(message = "jwt.refresh-expiration-days must be greater than zero")
        @DefaultValue("7")
        int refreshExpirationDays,

        @NotBlank
        @DefaultValue("career-assistant")
        String issuer,

        @NotBlank
        @DefaultValue("career-assistant-web")
        String audience) {

    /** Access token lifetime in milliseconds, which is what the jjwt builder wants. */
    public long expirationMillis() {
        return expiration.toMillis();
    }
}
