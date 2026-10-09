package com.careerplatform.career_assistant.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures OpenAPI documentation for the application's REST API.
 *
 * Documents available endpoints, request and response structures, and
 * authentication requirements to help developers understand and test the API.
 *
 * Defines the HTTP Bearer authentication scheme so Swagger UI can include
 * the access token in the Authorization header when testing protected endpoints.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI careerAssistantOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Career Assistant API")
                        .version("v1")
                        .description("""
                                Resume analysis and career roadmap generation.

                                Authentication: POST /api/v1/auth/login returns an access
                                token in the body and sets a httpOnly refresh cookie
                                scoped to /api/v1/auth. Send the access token as
                                `Authorization: Bearer <token>`.

                                Errors share one shape: status, message, timestamp and a
                                requestId that also appears on the X-Request-Id header.
                                """))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                // Applied globally; the auth endpoints are the exception, not the rule.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
