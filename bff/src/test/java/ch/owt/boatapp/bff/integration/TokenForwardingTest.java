package ch.owt.boatapp.bff.integration;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

import static ch.owt.boatapp.bff.support.SessionTestSupport.authenticatedSession;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the BFF's outbound RestClient interceptor (declared in
 * {@code BffConfig.businessServiceRestClient}) attaches an
 * {@code Authorization: Bearer ...} header to every upstream call, sourced
 * from the session's {@code OAuth2AuthorizedClient}. The synthetic
 * authorized client injected by Spring Security's
 * {@code SecurityMockMvcRequestPostProcessors.oauth2Login()} carries a stub
 * access token that is sufficient to verify the header is present.
 */
class TokenForwardingTest extends BffIntegrationTestBase {

    /**
     * GET /api/v1/boats with an authenticated session → WireMock receives
     * the upstream call carrying {@code Authorization: Bearer <token>}.
     */
    @Test
    void authenticatedRequest_attachesBearerToken_onUpstreamCall() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/api/v1/boats"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"content":[],"totalElements":0,"totalPages":0,
                                 "size":10,"number":0,"first":true,"last":true,"empty":true}
                                """)));

        mockMvc.perform(get("/api/v1/boats").with(authenticatedSession()))
                .andExpect(status().isOk());

        wireMock.verify(getRequestedFor(urlEqualTo("/api/v1/boats?page=0&size=10&sort=createdAt%2Cdesc"))
                .withHeader("Authorization", matching("Bearer .+")));
    }
}
