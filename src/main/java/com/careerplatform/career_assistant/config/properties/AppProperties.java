package com.careerplatform.career_assistant.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * Everything under the {@code app.} prefix.
 *
 * Grouped into one record rather than five because these are all "how this deployment
 * behaves" settings and a reader looking for one of them should not have to know which
 * of five files it lives in.
 *
 * The nested records carry the constraints, so a bad value fails at startup naming the
 * exact property rather than at the first request that happens to read it.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(

        @NotNull @Valid Cookie cookie,
        @NotNull @Valid Cors cors,
        @NotNull @Valid Generation generation,
        @NotNull @Valid RateLimit rateLimit,
        @DefaultValue @NotNull @Valid Account account) {

    /**
     * Account deletion (7.1).
     *
     * {@code deletionGracePeriod} is how long a deletion request sits pending before the
     * data is actually destroyed. It is a trade-off in one number: too short and the
     * window does not survive a weekend, too long and "delete my account" stops being an
     * honest description of what happened. Seven days is the usual compromise.
     *
     * The bare {@code @DefaultValue} on the component above means this record is still
     * constructed - with these defaults - when no {@code app.account.*} property is set
     * at all. Without it the record would bind as null and fail the {@code @NotNull}.
     */
    public record Account(
            @NotNull @DefaultValue("7d") Duration deletionGracePeriod) {

        /**
         * When a deletion requested at {@code requestedAt} will actually execute, or
         * null when nothing is pending.
         *
         * Lives here rather than in a service so the two places that need it - the
         * settings service and the profile response - cannot compute it differently and
         * show the user two different dates.
         */
        public java.time.LocalDateTime purgeTimeFor(java.time.LocalDateTime requestedAt) {
            return requestedAt == null ? null : requestedAt.plus(deletionGracePeriod);
        }
    }

    /**
     * Refresh token cookie attributes.
     *
     * {@code secure} defaults to true. A Secure cookie is not stored over plain http, so
     * local development over http needs {@code app.cookie.secure=false} - otherwise login
     * returns 200 and every subsequent refresh returns 401 because the cookie was never
     * saved.
     *
     * {@code sameSite} is load-bearing rather than decorative: CSRF protection is off and
     * the refresh endpoint accepts this cookie, so Strict is the only thing stopping
     * another origin forcing a token rotation. The pattern below rejects a typo like
     * "strict " or "None" with a startup failure instead of a silently permissive cookie.
     */
    public record Cookie(
            @DefaultValue("true") boolean secure,

            @Pattern(regexp = "Strict|Lax|None",
                    message = "app.cookie.same-site must be exactly Strict, Lax or None")
            @DefaultValue("Strict") String sameSite) {
    }

    /** Browser origins allowed to call this API. */
    public record Cors(
            @NotEmpty(message = "app.cors.allowed-origins must list at least one origin")
            List<String> allowedOrigins) {
    }

    /**
     * Monthly generation quota.
     *
     * {@code resetZone} is a ZoneId rather than a String so an invalid timezone fails at
     * startup. It was previously parsed with {@code ZoneId.of()} on every quota check,
     * which turned a configuration typo into a 500 on the first generation of the month.
     */
    public record Generation(
            @Positive(message = "app.generation.free-limit must be greater than zero")
            int freeLimit,

            @NotNull @DefaultValue("Asia/Kolkata") ZoneId resetZone) {
    }

    /**
     * Whether to trust X-Forwarded-For when identifying a client.
     *
     * Leave false when the app is directly reachable: the header is client-supplied, so
     * if nothing strips it an attacker sends a new value per request, every request looks
     * like a new client, and rate limiting never fires. Set true only when a proxy you
     * control overwrites the header first.
     */
    public record RateLimit(@DefaultValue("false") boolean trustForwardedHeaders) {
    }

    /*
     * app.refresh-token.purge-cron is deliberately NOT bound here. @Scheduled takes a
     * String placeholder and cannot read a bean method, so binding it as well would give
     * the setting two homes and let them disagree. It stays a placeholder on
     * RefreshTokenService.purgeExpiredTokens.
     */
}
