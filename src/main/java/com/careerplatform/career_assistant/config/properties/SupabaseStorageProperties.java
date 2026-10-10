package com.careerplatform.career_assistant.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Supabase Storage.
 * Bound from the {@code supabase} prefix in application properties.
 */
@Validated
@ConfigurationProperties(prefix = "supabase")
public record SupabaseStorageProperties(

        @NotBlank(message = "supabase.url must be set")
        String url,

        @NotBlank(message = "supabase.secret-key must be set")
        String secretKey,

        @NotBlank(message = "supabase.bucket must be set")
        String bucket
) {
    /**
     * Helper to get the normalized base URL without trailing slash.
     */
    public String normalizedUrl() {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
