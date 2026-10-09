package com.careerplatform.career_assistant.config;

import com.careerplatform.career_assistant.ratelimit.RateLimitInterceptor;
import com.careerplatform.career_assistant.security.CurrentUserIdArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring MVC setup: the API version prefix, the per-user rate limit interceptor, and the
 * {@code @CurrentUserId} argument resolver.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** Base package whose controllers get the version prefix. */
    private static final String CONTROLLER_PACKAGE =
            "com.careerplatform.career_assistant.controller";

    private final RateLimitInterceptor rateLimitInterceptor;
    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

    /**
     * 6.8: applies {@link ApiPaths#BASE} to every controller in the package, so each
     * controller declares only its own segment.
     *
     * Doing it here rather than writing "/api/v1/auth" into each @RequestMapping means a
     * future /api/v2 is one constant, and - more importantly right now - it is impossible
     * for one controller to be left behind on the old prefix. A controller that silently
     * kept "/api/..." would not fail any test; it would just sit outside the versioned
     * surface and outside the permitAll list, returning 401 to a frontend that had been
     * updated correctly.
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                ApiPaths.BASE, HandlerTypePredicate.forBasePackage(CONTROLLER_PACKAGE));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Still /api/** - /api/v1/** is a subset of it, so the per-user limits keep
        // applying and would also cover a future /api/v2.
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
    }

    /** 6.1: lets controllers declare `@CurrentUserId Long userId`. */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdArgumentResolver);
    }
}
