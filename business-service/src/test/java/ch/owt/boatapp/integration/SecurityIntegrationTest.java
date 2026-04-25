package ch.owt.boatapp.integration;

import org.junit.jupiter.api.Test;

import static ch.owt.boatapp.support.JwtTestSupport.mockJwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the resource-server's authorization rules: protected endpoints
 * require a JWT, public endpoints (actuator health, swagger UI) don't.
 */
class SecurityIntegrationTest extends IntegrationTestBase {

    /** Protected endpoint without auth → 401. */
    @Test
    void protectedEndpoint_withoutJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/boats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://boatapp.owt.ch/problems/auth-required"));
    }

    /** Protected endpoint with JWT → 200. */
    @Test
    void protectedEndpoint_withJwt_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/boats").with(mockJwt()))
                .andExpect(status().isOk());
    }

    /** Actuator health is public — no JWT required. */
    @Test
    void actuatorHealth_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
