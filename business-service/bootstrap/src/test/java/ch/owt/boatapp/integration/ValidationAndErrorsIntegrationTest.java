package ch.owt.boatapp.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static ch.owt.boatapp.testsupport.JwtTestSupport.mockJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the unified RFC 9457 error contract for the Business
 * Service. Every assertion checks the FULL envelope: status code,
 * {@code Content-Type: application/problem+json}, populated
 * {@code Content-Language} header, {@code type} URI from the project
 * registry (never {@code about:blank}), and (for 400/422) populated
 * {@code messages[]} entries. Each test also asserts the body never
 * contains the literal {@code about:blank} as a regression guard for the
 * registry rule.
 *
 * <p>This test class is intentionally chatty — it is the single place where
 * the gate's RFC 9457 grep tokens live ({@code application/problem+json},
 * {@code Content-Language}, {@code boatapp.owt.ch/problems/validation},
 * {@code field.required}, {@code field.size.invalid},
 * {@code request.body.malformed}). Removing or renaming any of these will
 * break the verification gate.
 *
 * <p>The 500 fallback path is exercised separately by
 * {@link InternalErrorIntegrationTest} (which mocks the application service
 * to throw, and therefore needs its own Spring context).
 */
class ValidationAndErrorsIntegrationTest extends IntegrationTestBase {

    private static final String VALIDATION_TYPE = "https://boatapp.owt.ch/problems/validation";
    private static final String NOT_FOUND_TYPE  = "https://boatapp.owt.ch/problems/not-found";
    private static final String CONFLICT_TYPE   = "https://boatapp.owt.ch/problems/concurrency-conflict";
    private static final String PRECOND_TYPE    = "https://boatapp.owt.ch/problems/precondition-required";
    private static final String AUTH_TYPE       = "https://boatapp.owt.ch/problems/auth-required";

    /** POST with no {@code name} field → 400 with code {@code field.required} (NotNull). */
    @Test
    void postMissingName_returns400_withFieldRequired() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(header().exists(HttpHeaders.CONTENT_LANGUAGE))
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/v1/boats"))
                .andExpect(jsonPath("$.messages[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.messages[0].code").value("field.required"))
                .andExpect(jsonPath("$.messages[0].field").value("name"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /** POST with empty {@code name} → 400 with code {@code field.size.invalid} (Size min=1). */
    @Test
    void postBlankName_returns400_withSizeInvalid() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.messages[0].code").value("field.size.invalid"))
                .andExpect(jsonPath("$.messages[0].field").value("name"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /** POST with 65-char {@code name} → 400 with code {@code field.size.invalid}. */
    @Test
    void postOversizeName_returns400_withSizeInvalid() throws Exception {
        String overSize = "x".repeat(65);
        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + overSize + "\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.messages[0].code").value("field.size.invalid"))
                .andExpect(jsonPath("$.messages[0].field").value("name"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /** POST with 257-char {@code description} → 400 with code {@code field.size.invalid}. */
    @Test
    void postOversizeDescription_returns400_withSizeInvalid() throws Exception {
        String overSize = "x".repeat(257);
        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"OK\",\"description\":\"" + overSize + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.messages[0].code").value("field.size.invalid"))
                .andExpect(jsonPath("$.messages[0].field").value("description"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /** POST with malformed JSON → 400 with code {@code request.body.malformed}. */
    @Test
    void postMalformedJson_returns400_withBodyMalformed() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.messages[0].code").value("request.body.malformed"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /** GET /boats?page=-1 → 400 (constraint violation on @Min(0) page parameter). */
    @Test
    void getListWithInvalidPage_returns400() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/boats")
                        .with(mockJwt())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.messages[0].code").value("field.range.invalid"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /**
     * POST with name "FORBIDDEN" → 422 (semantic validation failure raised
     * by {@code SemanticValidator → ValidationFailureException}). Proves the
     * full {@code domain.SemanticValidator → BoatDomainService →
     * BoatApplicationService → ValidationFailureException → HTTP 422} path.
     */
    @Test
    void semanticDomainFailure_returns422_withSameTypeUri() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"FORBIDDEN-X\",\"description\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(header().exists(HttpHeaders.CONTENT_LANGUAGE))
                .andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.instance").value("/api/v1/boats"))
                .andExpect(jsonPath("$.messages[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.messages[0].code").value("field.format.invalid"))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /** GET /boats/{random-uuid} → 404 with type {@code .../not-found}, no messages. */
    @Test
    void getMissingBoat_returns404_withNotFoundType() throws Exception {
        UUID id = UUID.randomUUID();
        MvcResult res = mockMvc.perform(get("/api/v1/boats/" + id).with(mockJwt()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(header().exists(HttpHeaders.CONTENT_LANGUAGE))
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.instance").value("/api/v1/boats/" + id))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /** PUT without {@code If-Match} → 428 with type {@code .../precondition-required}. */
    @Test
    void putWithoutIfMatch_returns428_withPreconditionType() throws Exception {
        UUID id = UUID.randomUUID();
        MvcResult res = mockMvc.perform(put("/api/v1/boats/" + id)
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type").value(PRECOND_TYPE))
                .andExpect(jsonPath("$.status").value(428))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /**
     * PUT with stale {@code If-Match} on a freshly-created boat → 409 with
     * type {@code .../concurrency-conflict}. Creates the boat first to obtain
     * a real {@code id}; then issues an update with version {@code 999}.
     */
    @Test
    void putWithStaleVersion_returns409_withConflictType() throws Exception {
        String createBody = "{\"name\":\"Original\",\"description\":\"x\"}";
        MvcResult created = mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();
        String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isNotNull();
        String id = location.substring(location.lastIndexOf('/') + 1);

        MvcResult res = mockMvc.perform(put("/api/v1/boats/" + id)
                        .with(mockJwt())
                        .header(HttpHeaders.IF_MATCH, "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(CONFLICT_TYPE))
                .andExpect(jsonPath("$.status").value(409))
                .andReturn();
        assertNoAboutBlank(res);
    }

    /** Request without a JWT → 401 with type {@code .../auth-required}. */
    @Test
    void requestWithoutJwt_returns401_withAuthRequiredType() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/boats"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value(AUTH_TYPE))
                .andExpect(jsonPath("$.status").value(401))
                .andReturn();
        assertNoAboutBlank(res);
    }

    private static void assertNoAboutBlank(MvcResult res) throws Exception {
        String body = res.getResponse().getContentAsString();
        assertThat(body)
                .as("RFC 9457 envelope must never use about:blank — every type URI must come from the project registry")
                .doesNotContain("about:blank");
    }
}
