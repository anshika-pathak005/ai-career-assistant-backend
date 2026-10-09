package com.careerplatform.career_assistant.util;

import com.careerplatform.career_assistant.config.ApiPaths;
import com.careerplatform.career_assistant.config.properties.AppProperties;
import com.careerplatform.career_assistant.config.properties.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Reads, sets and clears the refresh token cookie.
 * The cookie is httpOnly, scoped to the auth endpoints only, and its Secure and SameSite
 * attributes come from configuration.
 *
 * Setting and clearing both go through one builder, because a browser only replaces a
 * cookie when the attributes match — building them separately is how logout silently
 * stops clearing the cookie.
 */
@Component
@RequiredArgsConstructor
public class CookieUtil {

    public static final String REFRESH_TOKEN_COOKIE = "refreshToken";

    /**
     * Narrow on purpose: the token is never sent to non-auth endpoints.
     *
     * 6.8: this moved with the rest of the API under /api/v1, so it is derived from
     * ApiPaths rather than written out again. A cookie set at one path is not sent to
     * another, so a mismatch here does not fail loudly - it just means refresh stops
     * working and every session silently dies at the access token's expiry.
     */
    private static final String COOKIE_PATH = ApiPaths.AUTH;

    private final JwtProperties jwtProperties;
    private final AppProperties appProperties;

    /** Sets the refresh token cookie on the response. */
    public void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                build(refreshToken, Duration.ofDays(jwtProperties.refreshExpirationDays())).toString());
    }

    /** Clears the refresh token cookie, using the same attributes it was set with. */
    public void clearRefreshTokenCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    /** Reads the refresh token the browser sent, if any. */
    public Optional<String> getRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();

        return Arrays.stream(request.getCookies())
                .filter(cookie -> REFRESH_TOKEN_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /** Single definition of the cookie's attributes, used by both set and clear. */
    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(appProperties.cookie().secure())
                .sameSite(appProperties.cookie().sameSite())
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
