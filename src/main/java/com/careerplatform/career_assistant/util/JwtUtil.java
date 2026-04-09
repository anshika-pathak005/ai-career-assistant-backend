package com.careerplatform.career_assistant.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Slf4j
@Component
public class JwtUtil {

    // loaded from application.properties — never hardcode this
    @Value("${jwt.secret}")
    private String secret;

    // loaded from application.properties — token expiry time in ms
    @Value("${jwt.expiration}")
    private Long expiration;

    // builds a signing key from the secret string
    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    /**
     * Generates a JWT token with userId and email embedded as claims.
     * Claims = data stored inside the token (visible after decoding, but tamper-proof)
     */
    public String generateToken(Long userId, String email) {
        log.info("Generating JWT token for userId: {}", userId);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))   // userId as subject
                .claim("email", email)                // email as custom claim
                .setIssuedAt(new Date())              // token created at
                .setExpiration(new Date(System.currentTimeMillis() + expiration)) // token expires at
                .signWith(getSigningKey(), SignatureAlgorithm.HS256) // sign with secret
                .compact();
    }

    /**
     * Extracts all claims from token.
     * This is the base method — extractUserId and extractEmail use this internally.
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    // extracts userId from token subject
    public Long extractUserId(String token) {
        String subject = extractAllClaims(token).getSubject();
        return Long.parseLong(subject);
    }

    // extracts email from token claims
    public String extractEmail(String token) {
        return extractAllClaims(token).get("email", String.class);
    }

    // checks if token expiry date is before current time
    private boolean isTokenExpired(String token) {
        return extractAllClaims(token).getExpiration().before(new Date());
    }

    /**
     * Validates token — checks signature is valid and token is not expired.
     * Returns false if token is tampered, malformed, or expired.
     */
    public boolean validateToken(String token) {
        try {
            extractAllClaims(token); // throws exception if invalid/tampered
            return !isTokenExpired(token);
        } catch (Exception ex) {
            log.error("Invalid JWT token: {}", ex.getMessage());
            return false;
        }
    }
}