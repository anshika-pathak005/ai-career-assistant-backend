package com.careerplatform.career_assistant.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import com.careerplatform.career_assistant.config.properties.JwtProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and parses JWT access tokens.
 *
 * Each token carries the userId as its subject, a "tv" token-version claim used for
 * revocation, a unique jti, and an issuer and audience that are required when parsing.
 * The secret and expiry are validated at startup, so a bad configuration stops the app
 * booting rather than failing on the first login.
 *
 * The email is deliberately not a claim. A JWT is base64, not encrypted, and these
 * tokens sit in localStorage and get pasted into bug reports.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    /** HS256 requires a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    public static final String CLAIM_TOKEN_VERSION = "tv";

    /**
     * 6.4: was four @Value fields. One validated record now, so a missing or short
     * secret fails at startup with a message naming the property.
     */
    private final JwtProperties properties;

    /** Built once at startup rather than on every call. */
    private Key signingKey;

    /**
     * Validates the JWT configuration and builds the signing key.
     * Throws, and so prevents startup, if the secret is missing or shorter than
     * 256 bits — jjwt would otherwise only complain at the first token generation.
     */
    @PostConstruct
    void init() {
        // Explicit UTF-8: the platform default charset would produce different key bytes
        // on a machine with a different file.encoding, invalidating every issued token.
        byte[] keyBytes = properties.secret().getBytes(StandardCharsets.UTF_8);

        // JwtProperties already rejects a secret shorter than 32 CHARACTERS. This is
        // the exact check: HS256 needs 256 BITS, and character count is not byte count.
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(String.format(
                    "jwt.secret is %d bytes; HS256 requires at least %d (256 bits). " +
                    "Generate one with: openssl rand -base64 48",
                    keyBytes.length, MIN_SECRET_BYTES));
        }

        if (properties.expirationMillis() <= 0) {
            throw new IllegalStateException("jwt.expiration must be a positive duration");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT signing key initialised ({} bit) - issuer={}, audience={}, ttl={}ms",
                keyBytes.length * 8, properties.issuer(), properties.audience(),
                properties.expirationMillis());
    }

    /**
     * Issues an access token for a user at the given token version.
     * There is deliberately no overload taking an email — see the class Javadoc.
     */
    public String generateToken(Long userId, int tokenVersion) {
        Date now = new Date();

        return Jwts.builder()
                .setId(UUID.randomUUID().toString())            // jti
                .setSubject(String.valueOf(userId))
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .setIssuer(properties.issuer())
                .setAudience(properties.audience())
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + properties.expirationMillis()))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Parses a token and validates its signature, expiry, issuer and audience.
     * Requiring the issuer and audience stops a token minted by another environment
     * that happens to share the secret from being accepted here.
     * Throws JwtException, or IllegalArgumentException for a null or blank token.
     */
    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .requireIssuer(properties.issuer())
                .requireAudience(properties.audience())
                .setAllowedClockSkewSeconds(30)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Non-throwing variant of parse. Returns empty for a missing, malformed, expired,
     * tampered or foreign-issuer token.
     * Used by logout, which must complete cleanly whatever it is handed — a failure
     * there would tell the caller whether the token was still valid.
     */
    public Optional<Claims> tryParse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(token));
        } catch (JwtException | IllegalArgumentException ex) {
            // debug, not error: an expired token on a logout call is normal traffic
            log.debug("Token rejected: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Reads the userId from already-parsed claims. */
    public Long userIdFrom(Claims claims) {
        return Long.parseLong(claims.getSubject());
    }

    /**
     * Reads the token version from already-parsed claims.
     * Returns -1 when the claim is absent, which can never match a real tokenVersion,
     * so such a token fails the revocation check and the client refreshes.
     */
    public int tokenVersionFrom(Claims claims) {
        Integer version = claims.get(CLAIM_TOKEN_VERSION, Integer.class);
        return version != null ? version : -1;
    }
}
