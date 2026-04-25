package ch.owt.boatapp.bff.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import com.github.tomakehurst.wiremock.client.WireMock;

import static ch.owt.boatapp.bff.support.SessionTestSupport.authenticatedSession;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the BFF wraps upstream 5xx responses as
 * {@code 502 Bad Gateway} with type {@code .../upstream-failure} and never
 * leaks the upstream body to the browser (the upstream body is an internal
 * detail).
 */
class BffUpstreamFailureIntegrationTest extends BffIntegrationTestBase {

    private static final String UPSTREAM_FAILURE_TYPE =
            "https://boatapp.owt.ch/problems/upstream-failure";

    /** Upstream 500 → BFF 502 with synthetic ProblemDetail; upstream body NOT leaked. */
    @Test
    void upstream500_isWrappedAs502_withoutLeakingBody() throws Exception {
        wireMock.stubFor(WireMock.get(urlMatching("/api/v1/boats/.*"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("INTERNAL_DETAILS_FROM_UPSTREAM")));

        MvcResult res = mockMvc.perform(get("/api/v1/boats/" + UUID.randomUUID())
                        .with(authenticatedSession()))
                .andExpect(status().isBadGateway())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value(UPSTREAM_FAILURE_TYPE))
                .andExpect(jsonPath("$.status").value(502))
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertThat(body)
                .as("Upstream body must never leak to the browser")
                .doesNotContain("INTERNAL_DETAILS_FROM_UPSTREAM");
        assertThat(body).doesNotContain("about:blank");
    }
}
