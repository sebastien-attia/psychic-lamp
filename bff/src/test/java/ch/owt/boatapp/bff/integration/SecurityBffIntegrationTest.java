package ch.owt.boatapp.bff.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static ch.owt.boatapp.bff.support.SessionTestSupport.authenticatedSession;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the BFF's authorization rules: protected {@code /api/**}
 * endpoints require a valid OAuth2 session and respond with an RFC 9457 401
 * envelope (NOT a 302 redirect — those are for the SPA's HTML routes).
 * Public endpoints (actuator health, JWKS) require no session.
 */
class SecurityBffIntegrationTest extends BffIntegrationTestBase {

    /**
     * GET /api/** without session → 401 RFC 9457 (not a 302 redirect).
     * The session-based redirect to Keycloak is only used for non-API
     * routes; AJAX clients receive a 401 ProblemDetail they can act on.
     */
    @Test
    void apiEndpoint_withoutSession_returns401_problemJson() throws Exception {
        mockMvc.perform(get("/api/v1/boats"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(jsonPath("$.type")
                        .value("https://boatapp.owt.ch/problems/auth-required"));
    }

    /** GET /actuator/health is public — no session required. */
    @Test
    void actuatorHealth_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    /** GET /.well-known/jwks.json is public and returns the BFF's public JWK. */
    @Test
    void jwks_isPublic_andReturnsKeySet() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray());
    }

    /**
     * GET /api/** with an authenticated session → forwards through (no 401).
     * Without a WireMock stub the BFF cannot reach the upstream and wraps
     * the failure as 502 {@code .../upstream-failure}. The point of this
     * test is to pin down that the security layer accepted the session, not
     * to assert any particular upstream-failure code — hence the explicit
     * {@code !=401} assertion.
     */
    @Test
    void apiEndpoint_withSession_doesNotReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/boats/00000000-0000-0000-0000-000000000001")
                        .with(authenticatedSession()))
                .andExpect(status().is(not(401)));
    }
}
