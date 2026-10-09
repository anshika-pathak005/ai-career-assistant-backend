package com.careerplatform.career_assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: the whole context starts.
 *
 * 7.4 — this is a weaker test than it looks, and also the single most valuable one in
 * the project right now. It does not assert any behaviour, but it does catch every
 * failure mode this refactor introduced: a missing bean, an ambiguous one, a
 * @ConfigurationProperties record that does not bind, a filter registered twice, a
 * @Scheduled cron expression that does not parse, and a validation constraint on a
 * property that no profile satisfies.
 *
 * Phases 1–6 were all shipped without one running. This is the first phase where CI
 * boots the application.
 *
 * @ActiveProfiles("ci") supplies throwaway values for the properties 6.4 made mandatory;
 * without it the context cannot start and this test fails everywhere including locally.
 */
@SpringBootTest
@ActiveProfiles("ci")
class CareerAssistantApplicationTests {

	@Test
	void contextLoads() {
	}

}
