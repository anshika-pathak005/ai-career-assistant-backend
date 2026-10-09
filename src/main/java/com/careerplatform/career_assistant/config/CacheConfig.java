package com.careerplatform.career_assistant.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Enables caching and defines the in-memory caches.
 *
 * Only specialization lookups are cached: they are a pure function of a job title,
 * the same few titles are requested constantly, and every hit is a Groq call saved.
 * Nothing user-specific is cached, so no request can see another user's data.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SPECIALIZATIONS = "specializations";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(SPECIALIZATIONS);
        manager.setCaffeine(Caffeine.newBuilder()
                // Bounded, because the key is user-supplied — an unbounded cache keyed
                // on user input can be filled with random strings until the heap runs out.
                .maximumSize(1_000)
                // A day: specialization lists for a job title do not change hour to hour,
                // and the long TTL is where the saving comes from.
                .expireAfterWrite(Duration.ofHours(24)));
        return manager;
    }
}
