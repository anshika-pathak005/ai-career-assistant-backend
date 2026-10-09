package com.careerplatform.career_assistant.config;

/**
 * Centralizes API endpoint path definitions used throughout the application.
 *
 * This class provides shared constants for the base API path and commonly
 * used authentication endpoints. Other components, such as security
 * configuration, authentication filters, rate limiting, and cookie
 * configuration, can reference these constants instead of defining the
 * same paths independently.
 *
 * Keeping endpoint paths in one place reduces duplication and helps prevent
 * inconsistencies when different components need to identify the same route.
 * Paths can also be constructed from a shared base path, making their
 * relationship explicit and easier to maintain.
 *
 * This class is a utility class containing only static constants, so it
 * cannot be instantiated.
 */
public final class ApiPaths {

    /** Versioned root for every endpoint in the application. */
    public static final String BASE = "/api";

    /** Endpoints reachable without a token. Kept together so the three users agree. */
    public static final String AUTH = BASE + "/auth";

    public static final String REGISTER_STEP1 = AUTH + "/register/step1";
    public static final String LOGIN = AUTH + "/login";
    public static final String REFRESH = AUTH + "/refresh";
    public static final String LOGOUT = AUTH + "/logout";

    private ApiPaths() {
    }
}
