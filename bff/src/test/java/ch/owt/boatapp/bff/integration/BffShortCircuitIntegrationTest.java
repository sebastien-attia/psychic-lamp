package ch.owt.boatapp.bff.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static ch.owt.boatapp.bff.support.SessionTestSupport.authenticatedSession;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The BFF is a trust boundary at the browser edge. It must reject malformed
 * payloads with its OWN {@code @Valid} pipeline before forwarding to the
 * upstream Business Service. These tests prove the short-circuit by
 * asserting WireMock received zero requests on every BFF-side validation
 * failure.
 *
 * <p>The string {@code field.required} (and the other gate-required codes
 * found here and in {@link BffValidationForwardingIntegrationTest}) is
 * grepped by the verification gate — removing or renaming any of them will
 * break the build.
 */
class BffShortCircuitIntegrationTest extends BffIntegrationTestBase {

    private static final String VALIDATION_TYPE = "https://boatapp.owt.ch/problems/validation";

    /**
     * POST with no {@code name} field → 400 (BFF's @NotNull fires, code
     * {@code field.required}). WireMock must receive ZERO requests.
     */
    @Test
    void postMissingName_shortCircuits_withFieldRequired() throws Exception {
        mockMvc.perform(post("/api/v1/boats")
                        .with(authenticatedSession())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(header().exists(HttpHeaders.CONTENT_LANGUAGE))
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.messages[0].code").value("field.required"));
        wireMock.verify(exactly(0), anyRequestedFor(anyUrl()));
    }

    /**
     * POST with malformed JSON → 400 (BFF's
     * {@code HttpMessageNotReadableException} fires, code
     * {@code request.body.malformed}). WireMock must receive ZERO requests.
     */
    @Test
    void postMalformedJson_shortCircuits_withBodyMalformed() throws Exception {
        mockMvc.perform(post("/api/v1/boats")
                        .with(authenticatedSession())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.messages[0].code").value("request.body.malformed"));
        wireMock.verify(exactly(0), anyRequestedFor(anyUrl()));
    }

    /**
     * GET with {@code page=-1} → 400 (BFF's {@code @Min(0)} fires, code
     * {@code field.range.invalid}). WireMock must receive ZERO requests.
     */
    @Test
    void getNegativePage_shortCircuits() throws Exception {
        mockMvc.perform(get("/api/v1/boats")
                        .with(authenticatedSession())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.messages[0].code").value("field.range.invalid"));
        wireMock.verify(exactly(0), anyRequestedFor(anyUrl()));
    }
}
