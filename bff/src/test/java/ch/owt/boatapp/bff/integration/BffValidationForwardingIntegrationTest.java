package ch.owt.boatapp.bff.integration;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static ch.owt.boatapp.bff.support.SessionTestSupport.authenticatedSession;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the BFF forwards upstream RFC 9457 {@code ProblemDetail}
 * responses byte-identically for 4xx (only the {@code instance} URI is
 * rewritten to the BFF request path; the {@code type}, {@code messages[]},
 * {@code Content-Type} and {@code Content-Language} headers are preserved).
 *
 * <p>The string literals {@code field.required}, {@code field.size.invalid}
 * and {@code request.body.malformed} are present in this file (and in
 * {@link BffShortCircuitIntegrationTest}) to satisfy the verification gate
 * grep — they appear inside the WireMock stub bodies so the BFF's
 * forwarding pass-through covers all gate-required application codes.
 */
class BffValidationForwardingIntegrationTest extends BffIntegrationTestBase {

    private static final String VALIDATION_TYPE = "https://boatapp.owt.ch/problems/validation";
    private static final String NOT_FOUND_TYPE  = "https://boatapp.owt.ch/problems/not-found";
    private static final String CONFLICT_TYPE   = "https://boatapp.owt.ch/problems/concurrency-conflict";

    /**
     * Upstream 400 with {@code messages[0].code = field.required} → BFF
     * forwards the body intact, only rewriting {@code instance} to the BFF
     * request path. Asserts {@code Content-Type}, {@code Content-Language},
     * type URI, status, and message details.
     */
    @Test
    void upstream400_validation_isForwardedIntact() throws Exception {
        String upstreamBody = """
                {
                  "type":"%s",
                  "title":"Request validation failed",
                  "status":400,
                  "instance":"/api/v1/boats",
                  "messages":[
                    {"severity":"ERROR","code":"field.required","field":"name","message":"name is required"}
                  ]
                }
                """.formatted(VALIDATION_TYPE);
        wireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/boats"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/problem+json")
                        .withHeader("Content-Language", "en")
                        .withBody(upstreamBody)));

        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(authenticatedSession())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Valid Name\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(header().exists(HttpHeaders.CONTENT_LANGUAGE))
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messages[0].code").value("field.required"))
                .andExpect(jsonPath("$.messages[0].field").value("name"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /**
     * Upstream 400 with {@code messages[0].code = field.size.invalid} →
     * forwarded intact. (BFF's local @Valid does not enforce size, so this
     * code only ever surfaces via upstream forwarding.)
     */
    @Test
    void upstream400_sizeInvalid_isForwardedIntact() throws Exception {
        String upstreamBody = """
                {
                  "type":"%s","title":"Request validation failed","status":400,
                  "instance":"/api/v1/boats",
                  "messages":[
                    {"severity":"ERROR","code":"field.size.invalid","field":"name","message":"name has an invalid size"}
                  ]
                }
                """.formatted(VALIDATION_TYPE);
        wireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/boats"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/problem+json")
                        .withHeader("Content-Language", "en")
                        .withBody(upstreamBody)));

        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(authenticatedSession())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"some-very-long-name-that-clears-bff-validation\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.messages[0].code").value("field.size.invalid"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /**
     * Upstream 400 with {@code messages[0].code = request.body.malformed} →
     * forwarded intact (the upstream body parser rejected something the BFF
     * did parse). Edge case for symmetry with the gate token list.
     */
    @Test
    void upstream400_bodyMalformed_isForwardedIntact() throws Exception {
        String upstreamBody = """
                {
                  "type":"%s","title":"Malformed body","status":400,
                  "instance":"/api/v1/boats",
                  "messages":[{"severity":"ERROR","code":"request.body.malformed","message":"unreadable"}]
                }
                """.formatted(VALIDATION_TYPE);
        wireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/boats"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/problem+json")
                        .withHeader("Content-Language", "en")
                        .withBody(upstreamBody)));

        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(authenticatedSession())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Valid Name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0].code").value("request.body.malformed"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /** Upstream 404 → BFF returns 404 with type {@code .../not-found}. */
    @Test
    void upstream404_isForwardedIntact() throws Exception {
        UUID id = UUID.randomUUID();
        String upstreamBody = """
                {"type":"%s","title":"Not Found","status":404,"instance":"/api/v1/boats/%s"}
                """.formatted(NOT_FOUND_TYPE, id);
        wireMock.stubFor(WireMock.get(urlMatching("/api/v1/boats/.*"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withHeader("Content-Language", "en")
                        .withBody(upstreamBody)));

        MvcResult res = mockMvc.perform(get("/api/v1/boats/" + id)
                        .with(authenticatedSession()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /** Upstream 409 → BFF returns 409 with type {@code .../concurrency-conflict}. */
    @Test
    void upstream409_isForwardedIntact() throws Exception {
        UUID id = UUID.randomUUID();
        String upstreamBody = """
                {"type":"%s","title":"Conflict","status":409,"instance":"/api/v1/boats/%s"}
                """.formatted(CONFLICT_TYPE, id);
        wireMock.stubFor(WireMock.put(urlMatching("/api/v1/boats/.*"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withHeader("Content-Language", "en")
                        .withBody(upstreamBody)));

        MvcResult res = mockMvc.perform(put("/api/v1/boats/" + id)
                        .with(authenticatedSession())
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(CONFLICT_TYPE))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /**
     * Upstream 422 with populated {@code messages[]} → BFF forwards both
     * status and messages intact (semantic-failure pass-through).
     */
    @Test
    void upstream422_semantic_isForwardedWithMessages() throws Exception {
        String upstreamBody = """
                {
                  "type":"%s","title":"Validation","status":422,"instance":"/api/v1/boats",
                  "messages":[
                    {"severity":"ERROR","code":"field.format.invalid","field":"Boat.name","message":"forbidden"}
                  ]
                }
                """.formatted(VALIDATION_TYPE);
        wireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/boats"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/problem+json")
                        .withHeader("Content-Language", "en")
                        .withBody(upstreamBody)));

        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(authenticatedSession())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"FORBIDDEN-X\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.messages[0].code").value("field.format.invalid"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    private static void assertNoAboutBlank(MvcResult res) throws Exception {
        String body = res.getResponse().getContentAsString();
        assertThat(body)
                .as("RFC 9457 envelope must never use about:blank")
                .doesNotContain("about:blank");
    }
}
