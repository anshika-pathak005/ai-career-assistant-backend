package com.careerplatform.career_assistant.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an integration test: full application context, MockMvc, the `ci` profile.
 *
 * A meta-annotation rather than three annotations repeated on every class, because
 * Spring caches an application context per unique configuration. One class that spells
 * its annotations slightly differently gets its OWN context — a second full Spring
 * startup, a second Hibernate schema build — and the suite silently doubles in runtime.
 * Keeping the combination in one place makes that impossible.
 *
 * <b>MockMvc, not a real port.</b> These tests go through the entire filter chain
 * (RequestIdFilter, rate limiting, JWT authentication, the argument resolvers) and the
 * real controllers and services against a real database. What they skip is the HTTP
 * socket, which is the one layer with nothing of ours in it.
 *
 * <b>addFilters is left on.</b> It is tempting to disable the filters to make tests
 * simpler; that would remove exactly the code Phases 1–3 were about. A test that passes
 * with security switched off proves nothing about a system that runs with it on.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
public @interface IntegrationTest {
}
