package com.careerplatform.career_assistant.integration;

import com.careerplatform.career_assistant.config.properties.AppProperties;
import com.careerplatform.career_assistant.entity.CareerRoadmap;
import com.careerplatform.career_assistant.entity.User;
import com.careerplatform.career_assistant.repository.CareerRoadmapRepository;
import com.careerplatform.career_assistant.repository.UserRepository;
import com.careerplatform.career_assistant.service.UserSettingsService;
import com.careerplatform.career_assistant.support.IntegrationTest;
import com.careerplatform.career_assistant.support.TestDataFactory;
import com.careerplatform.career_assistant.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan items 7.1 and 5.4 — the account deletion grace period and the cascade purge.
 *
 * Two separate bugs live here and both are invisible without a test.
 *
 * <b>5.4:</b> the old deleteAccount claimed to cascade and did not. Resume,
 * AnalysisResult and CareerRoadmap each hold a @ManyToOne to User with no cascade, so
 * deleting the user hit a foreign key violation for anyone who had ever generated
 * anything. It appeared to work because it was only ever tried on a fresh account — which
 * is why the fixture here deliberately gives the user a full roadmap tree first.
 *
 * <b>7.1:</b> purgeDueAccounts calls purgeAccount, and the obvious way to write that —
 * @Transactional(REQUIRES_NEW) on purgeAccount — is silently broken. A self-invocation
 * never passes through the Spring proxy, so the annotation is ignored and the whole sweep
 * runs with no transaction at all. It fails at runtime, at 4am, in a job nobody watches.
 * The last test is the only thing that catches it.
 */
@IntegrationTest
@DisplayName("7.1/5.4 — deletion schedules, cancels, and purges the whole object graph")
class AccountDeletionLifecycleTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired CareerRoadmapRepository roadmapRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserSettingsService userSettingsService;
    @Autowired AppProperties appProperties;

    private TestDataFactory data;
    private User user;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(userRepository, roadmapRepository, passwordEncoder);
        user = data.user();
        // A user with data. Deleting an empty account proves nothing about 5.4.
        data.roadmapWithOneStep(user);
    }

    private String bearer() {
        return "Bearer " + jwtUtil.generateToken(user.getId(), user.getTokenVersion());
    }

    private String passwordBody() throws Exception {
        return objectMapper.writeValueAsString(
                java.util.Map.of("password", TestDataFactory.DEFAULT_PASSWORD));
    }

    @Test
    @DisplayName("DELETE returns 202 with a date and does NOT delete anything yet")
    void deleteSchedulesRatherThanDeletes() throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/v1/user/settings/account")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody()))
                // 202, not 204. Nothing has been deleted; 204 would say otherwise, and a
                // frontend rendering "your account has been deleted" on a 2xx would lie.
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("scheduledFor").asText()).isNotBlank();
        assertThat(body.get("cancellable").asBoolean()).isTrue();

        // The row is still there, marked.
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getDeletionRequestedAt()).isNotNull();
    }

    @Test
    @DisplayName("The user can still authenticate during the grace period")
    void sessionsSurviveTheRequest() throws Exception {
        mockMvc.perform(delete("/api/v1/user/settings/account")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody()))
                .andExpect(status().isAccepted());

        /*
         * Deliberately NOT revoking sessions is a decision, so it gets a test. The user
         * has to be able to log in to cancel — a frozen account that cannot be unfrozen
         * is a worse failure than a pending one that can. If someone later "hardens" this
         * by revoking tokens on request, this test tells them what they broke.
         */
        mockMvc.perform(post("/api/v1/user/settings/account/deletion/cancel")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Re-requesting does not slide the deletion date forward")
    void reRequestIsIdempotent() throws Exception {
        mockMvc.perform(delete("/api/v1/user/settings/account")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody()))
                .andExpect(status().isAccepted());

        LocalDateTime first = userRepository.findById(user.getId())
                .orElseThrow().getDeletionRequestedAt();

        mockMvc.perform(delete("/api/v1/user/settings/account")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody()))
                .andExpect(status().isAccepted());

        LocalDateTime second = userRepository.findById(user.getId())
                .orElseThrow().getDeletionRequestedAt();

        /*
         * If this slid forward, an attacker holding a live session could push the date out
         * indefinitely: the owner would see a pending deletion that never resolved and
         * never completed, and the account would be stuck in limbo permanently.
         */
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("Cancelling with the wrong password is refused and leaves the deletion pending")
    void cancelRequiresTheCorrectPassword() throws Exception {
        mockMvc.perform(delete("/api/v1/user/settings/account")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody()))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/user/settings/account/deletion/cancel")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeletionRequestedAt())
                .as("a failed cancel must not clear the pending deletion")
                .isNotNull();
    }

    @Test
    @DisplayName("Cancelling clears the pending deletion")
    void cancelClearsIt() throws Exception {
        mockMvc.perform(delete("/api/v1/user/settings/account")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody()))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/user/settings/account/deletion/cancel")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody()))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getDeletionRequestedAt())
                .isNull();
    }

    @Test
    @DisplayName("The purge sweep destroys the user and the whole roadmap tree")
    void purgeSweepRemovesEverything() {
        Long userId = user.getId();

        // Backdate the request past the grace period so the sweep picks it up. Faking the
        // clock rather than waiting: `ci` sets the grace period to 1s, but sleeping in a
        // test is how a suite becomes slow and then flaky.
        User pending = userRepository.findById(userId).orElseThrow();
        pending.setDeletionRequestedAt(
                LocalDateTime.now().minus(appProperties.account().deletionGracePeriod()).minusDays(1));
        userRepository.save(pending);

        /*
         * Called through the injected bean, so it goes via the Spring proxy exactly as
         * the scheduler would. This is what exercises the TransactionTemplate: with a
         * plain @Transactional(REQUIRES_NEW) on purgeAccount, the self-invocation inside
         * purgeDueAccounts would run with no transaction and this test fails.
         */
        userSettingsService.purgeDueAccounts();

        assertThat(userRepository.findById(userId))
                .as("the user row must be gone")
                .isEmpty();

        // 5.4: the FK violation case. If cascade or deletion order regresses, the purge
        // throws, the sweep swallows it as a per-account failure, and the user survives —
        // which the assertion above would catch, but this one says why.
        assertThat(roadmapRepository.findByUserId(userId))
                .as("roadmaps, phases, skills and steps must go with the user")
                .isEmpty();
    }

    @Test
    @DisplayName("An account still inside its grace period is left alone by the sweep")
    void purgeSweepIgnoresPendingAccounts() throws Exception {
        mockMvc.perform(delete("/api/v1/user/settings/account")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody()))
                .andExpect(status().isAccepted());

        userSettingsService.purgeDueAccounts();

        // The counterpart to the test above. A sweep with an inverted comparison would
        // delete everyone who ever requested, immediately, and the previous test would
        // still pass.
        assertThat(userRepository.findById(user.getId()))
                .as("the grace period must actually be honoured")
                .isPresent();
    }
}
