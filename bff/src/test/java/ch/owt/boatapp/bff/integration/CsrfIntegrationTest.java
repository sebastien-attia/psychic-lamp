package ch.owt.boatapp.bff.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.github.tomakehurst.wiremock.client.WireMock;

import static ch.owt.boatapp.bff.support.SessionTestSupport.authenticatedSession;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the BFF's cookie-based CSRF protection. A POST without the
 * {@code X-XSRF-TOKEN} header is rejected with 403; the same POST with the
 * CSRF token (injected by {@code SecurityMockMvcRequestPostProcessors.csrf()})
 * is accepted and forwarded to the upstream.
 */
class CsrfIntegrationTest extends BffIntegrationTestBase {

    /** Mutating request without CSRF token → 403 (and never reaches the upstream). */
    @Test
    void postWithoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/boats")
                        .with(authenticatedSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Black Pearl\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Mutating request with CSRF token → forwarded to upstream and 201
     * returned.
     *
     * <p>Stub uses {@code urlPathEqualTo} (not {@code urlEqualTo}) because
     * SCG MVC forwards the entire request URI byte-identically, including
     * the {@code ?_csrf=…} query parameter that
     * {@code SecurityMockMvcRequestPostProcessors.csrf()} appends. The
     * production SPA sends the CSRF token via the {@code X-XSRF-TOKEN}
     * header (no query string), so this test-only artefact does not affect
     * production wire shape.
     */
    @Test
    void postWithCsrf_isForwarded_andReturns201() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/api/v1/boats"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Location", "/api/v1/boats/abc")
                        .withHeader("ETag", "\"0\"")
                        .withBody("""
                                {"id":"00000000-0000-0000-0000-000000000001",
                                 "name":"Black Pearl","description":null,
                                 "createdAt":"2024-01-01T00:00:00Z","version":0}
                                """)));

        mockMvc.perform(post("/api/v1/boats")
                        .with(authenticatedSession())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Black Pearl\"}"))
                .andExpect(status().isCreated());
    }
}
