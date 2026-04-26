package ch.owt.boatapp.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static ch.owt.boatapp.testsupport.JwtTestSupport.mockJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Happy-path CRUD coverage for {@code /api/v1/boats}: create, read, update
 * with correct {@code If-Match}, list with pagination, search, delete.
 *
 * <p>Negative paths (validation 400, 422, 404, 409, 428) live in
 * {@link ValidationAndErrorsIntegrationTest}.
 */
class BoatControllerIntegrationTest extends IntegrationTestBase {

    /** POST → 201 with {@code Location} and {@code ETag} headers. */
    @Test
    void create_returns201_withLocationAndEtag() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Black Pearl\",\"description\":\"A pirate ship\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Black Pearl"))
                .andReturn();
        assertThat(res.getResponse().getHeader(HttpHeaders.ETAG)).isNotBlank();
    }

    /**
     * Full create → get → update → delete → get(404) round-trip, asserting
     * version bumps and ETag refresh.
     */
    @Test
    void create_get_update_delete_roundtrip() throws Exception {
        // Create
        MvcResult created = mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Argo\",\"description\":\"A trireme\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
        String etagV0 = created.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(location).isNotNull();
        assertThat(etagV0).isNotBlank();
        String id = location.substring(location.lastIndexOf('/') + 1);

        // GET should return same version
        mockMvc.perform(get("/api/v1/boats/" + id).with(mockJwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, etagV0))
                .andExpect(jsonPath("$.name").value("Argo"));

        // Update with the correct If-Match (strip surrounding quotes; version starts at 0).
        // Because BoatRepositoryAdapter.save uses saveAndFlush, the PUT response
        // already carries the bumped ETag — no GET-after-PUT round-trip needed.
        String version = etagV0.replace("\"", "");
        MvcResult updated = mockMvc.perform(put("/api/v1/boats/" + id)
                        .with(mockJwt())
                        .header(HttpHeaders.IF_MATCH, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Argo II\",\"description\":\"A trireme\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.name").value("Argo II"))
                .andReturn();
        String etagV1 = updated.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etagV1).isNotEqualTo(etagV0);

        // GET-after-PUT confirms the version persisted to storage matches the PUT response.
        mockMvc.perform(get("/api/v1/boats/" + id).with(mockJwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, etagV1))
                .andExpect(jsonPath("$.name").value("Argo II"));

        // DELETE
        mockMvc.perform(delete("/api/v1/boats/" + id).with(mockJwt()))
                .andExpect(status().isNoContent());

        // GET → 404
        mockMvc.perform(get("/api/v1/boats/" + id).with(mockJwt()))
                .andExpect(status().isNotFound());
    }

    /** Pagination: 15 boats, page=1 size=10 → 5 items returned. */
    @Test
    void list_with_pagination_returns_correct_window() throws Exception {
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(post("/api/v1/boats")
                            .with(mockJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"boat-" + i + "\"}"))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(get("/api/v1/boats")
                        .with(mockJwt())
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(15))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(5));
    }

    /** Search by partial name returns only matching rows. */
    @Test
    void list_with_search_filters_by_name() throws Exception {
        mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alpha-vessel\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"beta-vessel\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/boats")
                        .with(mockJwt())
                        .param("search", "alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("alpha-vessel"));
    }
}
