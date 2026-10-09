package com.careerplatform.career_assistant.integration;

import com.careerplatform.career_assistant.entity.CareerRoadmap;
import com.careerplatform.career_assistant.entity.SkillStep;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.repository.CareerRoadmapRepository;
import com.careerplatform.career_assistant.repository.SkillStepRepository;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.careerplatform.career_assistant.support.IntegrationTest;
import com.careerplatform.career_assistant.support.TestDataFactory;
import com.careerplatform.career_assistant.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan item 1.1 — the IDOR in step completion.
 *
 * <b>This is the most important test in the project.</b> The vulnerable version of
 * updateStepCompletion loaded the step by id and updated it, checking nothing about who
 * owned it — so any authenticated user could complete any other user's steps by guessing
 * a numeric id. It carried a Javadoc comment claiming the ownership check existed.
 *
 * A comment is not a control. The only thing that would have caught it is exactly this:
 * log in as B, send A's step id, assert it is refused.
 *
 * Read the assertions as a pair. "B is refused" on its own passes just as happily
 * against an endpoint that is broken for everybody, so the last test asserts that A can
 * still update their own step. A security test without its positive counterpart is how
 * you ship a fix that quietly breaks the feature.
 */
@IntegrationTest
@DisplayName("1.1 — a user cannot complete another user's roadmap step")
class RoadmapStepAuthorizationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired CareerRoadmapRepository roadmapRepository;
    @Autowired SkillStepRepository skillStepRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;

    private TestDataFactory data;

    private User alice;
    private User bob;
    private CareerRoadmap aliceRoadmap;
    private SkillStep aliceStep;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(userRepository, roadmapRepository, passwordEncoder);
        alice = data.user();
        bob = data.user();
        aliceRoadmap = data.roadmapWithOneStep(alice);
        aliceStep = TestDataFactory.onlyStepOf(aliceRoadmap);
    }

    /**
     * A real signed token rather than a mocked principal.
     *
     * @WithMockUser or a request post-processor would install an Authentication directly
     * and skip JwtAuthenticationFilter — which is where the tokenVersion revocation check
     * lives, and which is itself part of what these phases changed. Minting a genuine
     * token means the request travels the same path a browser's would.
     */
    private String bearerFor(User user) {
        return "Bearer " + jwtUtil.generateToken(user.getId(), user.getTokenVersion());
    }

    @Test
    @DisplayName("Bob passing Alice's roadmapId and stepId is refused")
    void bobCannotTouchAlicesStep() throws Exception {
        mockMvc.perform(patch("/api/v1/roadmap/{roadmapId}/step/{stepId}",
                        aliceRoadmap.getId(), aliceStep.getId())
                        .header("Authorization", bearerFor(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}"))
                // 404, not 403, and that is deliberate in the production code: a 403
                // would confirm the step exists, which is an enumeration oracle.
                .andExpect(status().isNotFound());

        // The HTTP status is not the assertion that matters. An endpoint could return
        // 404 and still have written the row. This is the real one.
        SkillStep reloaded = skillStepRepository.findById(aliceStep.getId()).orElseThrow();
        assertThat(reloaded.getCompleted())
                .as("Alice's step must be untouched after Bob's attempt")
                .isFalse();
    }

    @Test
    @DisplayName("Bob substituting his own roadmapId for Alice's stepId is refused")
    void bobCannotMixHisRoadmapWithAlicesStep() throws Exception {
        CareerRoadmap bobRoadmap = data.roadmapWithOneStep(bob);

        // This is the case a naive fix misses. Checking only "does this roadmap belong to
        // the caller" passes here — the roadmap IS Bob's — and then updates Alice's step
        // anyway. Both conditions have to be in the same query, which is why
        // SkillStepRepository.findOwnedStep takes all three ids.
        mockMvc.perform(patch("/api/v1/roadmap/{roadmapId}/step/{stepId}",
                        bobRoadmap.getId(), aliceStep.getId())
                        .header("Authorization", bearerFor(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}"))
                .andExpect(status().isNotFound());

        assertThat(skillStepRepository.findById(aliceStep.getId()).orElseThrow().getCompleted())
                .isFalse();
    }

    @Test
    @DisplayName("An unauthenticated request is rejected")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(patch("/api/v1/roadmap/{roadmapId}/step/{stepId}",
                        aliceRoadmap.getId(), aliceStep.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Alice CAN still complete her own step")
    void aliceCanCompleteHerOwnStep() throws Exception {
        mockMvc.perform(patch("/api/v1/roadmap/{roadmapId}/step/{stepId}",
                        aliceRoadmap.getId(), aliceStep.getId())
                        .header("Authorization", bearerFor(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}"))
                .andExpect(status().isNoContent());

        assertThat(skillStepRepository.findById(aliceStep.getId()).orElseThrow().getCompleted())
                .isTrue();
    }
}
