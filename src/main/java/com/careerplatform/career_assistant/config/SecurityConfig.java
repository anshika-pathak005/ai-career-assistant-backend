package com.careerplatform.career_assistant.config;

import com.careerplatform.career_assistant.config.properties.AppProperties;
import com.careerplatform.career_assistant.service.UserDetailsServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
// Enables @PreAuthorize and friends on service and controller methods.
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final UserDetailsServiceImpl userDetailsService;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    /**
     * Browser origins allowed to call this API.
     * Set per environment — put your dev machine's address in
     * application-local.properties rather than in this file.
     *
     * 6.4: was @Value. AppProperties requires the list to be non-empty, so a deployment
     * that forgets it fails at startup instead of serving an API no browser can call.
     */
    private final AppProperties appProperties;

    /**
     * Defines which endpoints are public and which require authentication.
     * Also applies CORS, security headers, and JSON error responses.
     *
     * STATELESS — no session is created on server, every request must carry its own JWT
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                /*
                 * CSRF is disabled because this is a bearer-token API — with one
                 * exception: /api/v1/auth/refresh accepts a cookie. SameSite=Strict on that
                 * cookie (see CookieUtil) is what stops another origin forcing a token
                 * rotation. If SameSite is ever relaxed, this line must change with it.
                 */
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny()) // prevent clickjacking
                        .contentTypeOptions(contentType -> {}) // X-Content-Type-Options: nosniff
                        // HSTS — Spring only emits this over HTTPS, so local http is unaffected.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        // A pure JSON API should never be a source for anything.
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                )
                // Return the app's ErrorResponse JSON for 401 and 403 instead of
                // Spring's default HTML error page.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints — everything else requires a valid JWT.
                        // register/step2 is intentionally not here: it requires a token.
                        // Keep this list in sync with PUBLIC_PATHS in JwtAuthenticationFilter.
                        .requestMatchers(
                                ApiPaths.REGISTER_STEP1,
                                ApiPaths.LOGIN,
                                ApiPaths.REFRESH,
                                ApiPaths.LOGOUT
                        ).permitAll()
                        /*
                         * 7.2 — actuator.
                         *
                         * These rules apply ONLY when the management endpoints share the
                         * application port, which is the local profile. In staging and
                         * prod management.server.port is 9090, which Spring Boot serves
                         * from a separate child context that this filter chain is not
                         * registered in — so on those profiles these lines do nothing
                         * and the port's safety comes entirely from not being reachable.
                         * That is worth knowing before someone reads this block and
                         * concludes actuator is authenticated in production.
                         *
                         * /health is open because a probe cannot hold a token; the
                         * details are suppressed by show-details, so it discloses a
                         * status word. Everything else — including /prometheus, which
                         * maps the whole API surface and its traffic — requires a login.
                         */
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).authenticated()
                        // OpenAPI docs. springdoc.api-docs.enabled=false turns these off
                        // entirely; see 6.8 and the note in application.properties.
                        .requestMatchers(
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/api/auth/login", "/api/auth/register", "/api/auth/refresh"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider())
                // register rate limiting filter before JWT filter
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                // register JWT filter — runs before Spring's default username/password filter
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // tells Spring Security how to load users and encode passwords
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    // needed to manually trigger authentication (used in login flow)
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    //CORS configuration — controls which origins, methods, headers are allowed.
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> allowedOrigins = appProperties.cors().allowedOrigins();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        log.info("CORS allowed origins: {}", allowedOrigins);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Stops the servlet container auto-registering the filters.
     *
     * Spring Boot registers any Filter bean automatically, so a @Component filter that is
     * also added via addFilterBefore runs twice per request. These beans keep the filters
     * injectable while leaving the security chain as the only place they run.
     *
     * Verify with: logging.level.org.springframework.security.web.FilterChainProxy=TRACE
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterAutoRegistrationDisabled(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitingFilter> rateLimitFilterAutoRegistrationDisabled(
            RateLimitingFilter filter) {
        FilterRegistrationBean<RateLimitingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Password encoder — uses BCrypt hashing algorithm.
     * BCrypt automatically handles salting, making it secure against rainbow table attacks.
     * Used in AuthService to hash passwords before saving to DB.
     *
     * AuthService generates its dummy comparison hash with this same bean at startup, so
     * the two always share a cost factor.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
