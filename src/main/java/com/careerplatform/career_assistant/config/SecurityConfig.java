package com.careerplatform.career_assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Defines which endpoints are public and which require authentication.
     * Also applies CORS and disables CSRF (not needed for stateless JWT APIs).
     *
     * STATELESS — no session is created on server, every request must carry its own auth (JWT later)
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // public endpoints — no token required
                        .requestMatchers(
                                "/api/auth/register/step1",
                                "/api/auth/register/step2",
                                "/api/auth/login"
                        ).permitAll()
                        // everything else requires authentication (JWT later)
                        .anyRequest().authenticated()
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        return http.build();
    }

    /**
     * CORS configuration — controls which origins, methods, headers are allowed.
     *
     * Allowed origins:
     *  - http://localhost:3000  → your Next.js frontend
     *  - http://localhost:8080  → Postman / direct API testing (same as server)
     *
     * In production, replace these with your actual deployed frontend URL.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(List.of(
                "http://localhost:3000",   // Next.js frontend
                "http://localhost:8080"    // Postman / API testing
        ));
        configuration.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "DELETE", "OPTIONS"
        ));
        configuration.setAllowedHeaders(List.of("*")); // allow all headers (Authorization, Content-Type etc.)
        configuration.setAllowCredentials(true);       // needed for cookies/auth headers

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // apply to all endpoints
        return source;
    }

    /**
     * Password encoder — uses BCrypt hashing algorithm.
     * BCrypt automatically handles salting, making it secure against rainbow table attacks.
     * Used in AuthService to hash passwords before saving to DB.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}