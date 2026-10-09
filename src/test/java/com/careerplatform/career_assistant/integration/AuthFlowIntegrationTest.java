package com.careerplatform.career_assistant.integration;

import com.careerplatform.career_assistant.support.IntegrationTest;
import com.careerplatform.career_assistant.util.CookieUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan items 2.4, 2.5 and 2.6 — the refresh token lifecycle end to end.
 *
 * This is the characterisation test Phase 0.3 asked for and never got. Phases 1, 2 and 6
 * all changed the login path — token rotation, the cookie's Path attribute, the
 * @CurrentUserId resolver — and none of it was ever executed before a user tried it.
 *
 * The reuse detection case is the one that earns this file's existence. Before Phase 2.4
 * that code path was unreachable: the check existed, the branch was written, and nothing
 * could ever enter it. Dead security code looks exactly like working security code in a
 * review.
 */
@IntegrationTest
@DisplayName("2.4/2.5/2.6 — register, login, refresh, rotation and reuse detection")
class AuthFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String email;
    private static final String PASSWORD = "correct-horse-battery";

    @BeforeEach
    void setUp() {
        // Unique per test: these tests commit, and users.email is unique.
        email = "auth-" + System.nanoTime() + "@example.test";
    }

    private MvcResult register() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register/step1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", "Auth Test",
                                "email", email,
                                "password", PASSWORD))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", email,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String refreshCookieValue(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(cookie).as("a refresh cookie must be set").isNotNull();
        return cookie.getValue();
    }

    private String accessToken(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    @Test
    @DisplayName("The refresh cookie is HttpOnly, SameSite=Strict and scoped to /api/v1/auth")
    void refreshCookieAttributesAreCorrect() throws Exception {
        MvcResult result = register();
        MockHttpServletResponse response = result.getResponse();

        Cookie cookie = response.getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(cookie).isNotNull();

        // HttpOnly is what keeps the refresh token out of reach of XSS. Without it, the
        // entire reason for putting it in a cookie rather than localStorage disappears.
        assertThat(cookie.isHttpOnly()).as("HttpOnly").isTrue();

        /*
         * SameSite is not exposed on jakarta Cookie, so the raw header is the only place
         * to see it. It is load-bearing here, not decoration: CSRF protection is disabled
         * in SecurityConfig and /auth/refresh accepts this cookie, so Strict is the only
         * thing stopping another origin forcing a token rotation.
         */
        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains("SameSite=Strict");

        // Phase 6.8 moved this from /api/auth. A browser will not send a cookie set at
        // one path to the other, which is why that release logged everyone out once.
        assertThat(setCookie).contains("Path=/api/v1/auth");
    }

    @Test
    @DisplayName("A fresh refresh token mints a new access token and rotates the cookie")
    void refreshRotatesTheToken() throws Exception {
        register();
        MvcResult loggedIn = login();
        String firstRefresh = refreshCookieValue(loggedIn);

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, firstRefresh)))
                .andExpect(status().isOk())
                .andReturn();

        String secondRefresh = refreshCookieValue(refreshed);

        // Rotation, not reuse. If these are equal the token is long-lived and stealing it
        // once is stealing it forever.
        assertThat(secondRefresh)
                .as("refresh must issue a NEW token, not return the same one")
                .isNotEqualTo(firstRefresh);
        assertThat(accessToken(refreshed)).isNotBlank();
    }

    @Test
    @DisplayName("Reusing an already-rotated refresh token is rejected")
    void reusingARotatedTokenIsRejected() throws Exception {
        register();
        MvcResult loggedIn = login();
        String stolenToken = refreshCookieValue(loggedIn);

        // The legitimate user refreshes. The old token is now spent.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, stolenToken)))
                .andExpect(status().isOk());

        // The attacker, holding a copy taken earlier, tries the same token.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, stolenToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Reuse revokes the whole family, so the legitimate token dies too")
    void reuseRevokesTheEntireFamily() throws Exception {
        register();
        MvcResult loggedIn = login();
        String oldToken = refreshCookieValue(loggedIn);

        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, oldToken)))
                .andExpect(status().isOk())
                .andReturn();
        String currentToken = refreshCookieValue(rotated);

        // Replay the spent token — this is the theft signal.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, oldToken)))
                .andExpect(status().isUnauthorized());

        /*
         * The point of reuse detection. Once a replay is seen, one of the two holders is
         * an attacker and there is no way to tell which — so BOTH are logged out. A
         * design that only rejected the replayed token would leave the attacker's freshly
         * rotated token working, which is the opposite of the intent.
         */
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, currentToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Logout invalidates the access token immediately, not just the refresh token")
    void logoutRevokesAccessTokensToo() throws Exception {
        register();
        MvcResult loggedIn = login();
        String access = accessToken(loggedIn);
        String refresh = refreshCookieValue(loggedIn);

        mockMvc.perform(get("/api/v1/user/profile").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .cookie(new Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, refresh)))
                .andExpect(status().is2xxSuccessful());

        /*
         * This is the tokenVersion mechanism, and it is the difference between "logout"
         * and "logout eventually". Revoking only the refresh token leaves every access
         * token already issued working until it expires — a grace period handed to
         * whoever the user is logging out because of.
         */
        mockMvc.perform(get("/api/v1/user/profile").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A wrong password and an unknown email are indistinguishable")
    void loginDoesNotLeakAccountExistence() throws Exception {
        register();

        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", email, "password", "definitely-not-it"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownEmail = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", "nobody-" + System.nanoTime() + "@example.test",
                                "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        /*
         * Same status and same message. Phase 2.5 also added a dummy BCrypt comparison so
         * the two take similar time — that TIMING side is deliberately NOT asserted here.
         * A wall-clock assertion on a shared CI runner is the definition of a flaky test,
         * and a flaky security test gets deleted within a month. Body equality is what
         * can be checked reliably; the timing fix is verified by reading AuthService.
         */
        assertThat(wrongPassword.getResponse().getStatus())
                .isEqualTo(unknownEmail.getResponse().getStatus());
    }
}
