package com.careerplatform.career_assistant.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

@Component
public class CookieUtil {

    @Value("${jwt.refresh-expiration-days:7}")
    private Long refreshExpirationDays;

    // cookie name — defined once here, used everywhere
    public static final String REFRESH_TOKEN_COOKIE = "refreshToken";

    /**
     * Sets refresh token as httpOnly cookie in the response.
     * Browser stores it automatically — JS cannot access it.
     */
    public void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE, refreshToken);

        cookie.setHttpOnly(true);                                    // JS cannot access
        cookie.setSecure(false);                                     // set true in production (HTTPS only)
        cookie.setPath("/api/auth");                                 // cookie only sent to /api/auth endpoints
        cookie.setMaxAge((int) (refreshExpirationDays * 24 * 60 * 60)); // 7 days in seconds

        // SameSite=Strict — cookie only sent to same domain, prevents CSRF
        // Spring doesn't have direct SameSite support in Cookie class, set via header
        response.addCookie(cookie);
        response.addHeader("Set-Cookie",
                String.format("%s=%s; HttpOnly; SameSite=Strict; Path=/api/auth; Max-Age=%d",
                        REFRESH_TOKEN_COOKIE,
                        refreshToken,
                        (int) (refreshExpirationDays * 24 * 60 * 60)
                )
        );
    }

    /**
     * Reads refresh token from incoming request cookies.
     * Browser sends it automatically — we just extract it here.
     */
    public Optional<String> getRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();

        return Arrays.stream(request.getCookies())
                .filter(cookie -> REFRESH_TOKEN_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    /**
     * Clears the refresh token cookie.
     * Called on logout — sets maxAge to 0, browser deletes it immediately.
     */
    public void clearRefreshTokenCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/api/auth");
        cookie.setMaxAge(0); // 0 = delete immediately
        response.addCookie(cookie);
    }
}