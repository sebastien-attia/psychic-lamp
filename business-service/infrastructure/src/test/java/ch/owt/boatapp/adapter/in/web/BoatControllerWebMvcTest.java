package ch.owt.boatapp.adapter.in.web;

import ch.owt.boatapp.application.port.in.CreateBoatCommand;
import ch.owt.boatapp.application.port.in.DeleteBoatCommand;
import ch.owt.boatapp.application.port.in.GetBoatQuery;
import ch.owt.boatapp.application.port.in.ListBoatsQuery;
import ch.owt.boatapp.application.port.in.UpdateBoatCommand;
import ch.owt.boatapp.application.service.BoatApplicationService;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.infrastructure.security.SecurityHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice covering only the web layer for {@link BoatController}.
 *
 * <p>Boots the controller, the global exception handler, the validation
 * pipeline and the JSON converter — but NOT the JPA layer, security filters
 * or any application services. Both {@link BoatApplicationService} and
 * {@link SecurityHelper} are replaced with Mockito beans so the test is
 * isolated from persistence and from the JWT filter chain. Security filters
 * are disabled with {@code addFilters = false}; the wired-up resource server
 * is exercised by the bootstrap-module integration tests.
 *
 * <p>This complements (does NOT replace) {@code BoatControllerIntegrationTest}
 * in the bootstrap module: the slice runs in milliseconds and pinpoints
 * adapter-only bugs (mapping, header writes, request binding, exception →
 * problem-detail wiring) without paying the Postgres-Testcontainer cost.
 */
@WebMvcTest(controllers = BoatController.class, excludeAutoConfiguration = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class,
        LiquibaseAutoConfiguration.class
})
@AutoConfigureMockMvc(addFilters = false)
// Explicit @Import is belt-and-braces: @WebMvcTest's `controllers` attribute
// targets BoatController, but the slice's package scan is rooted at the
// @SpringBootConfiguration's package and does not always reach down into
// adapter.in.web for either the controller or the @RestControllerAdvice.
// Spelling out the imports keeps the wiring explicit regardless of Spring
// Boot version-specific scan defaults.
@Import({BoatController.class, GlobalExceptionHandler.class})
class BoatControllerWebMvcTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private BoatApplicationService boatApplicationService;
    @MockitoBean private SecurityHelper securityHelper;

    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BOAT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    // -- POST -----------------------------------------------------------

    @Test
    void post_validBody_returns201_withLocationAndEtagHeaders() throws Exception {
        when(securityHelper.getCurrentAppUserId()).thenReturn(USER_ID);
        Boat created = new Boat(BOAT_ID, "Argo", "trireme", OffsetDateTime.now(ZoneOffset.UTC), 0L);
        when(boatApplicationService.createBoat(any(CreateBoatCommand.class))).thenReturn(created);

        MvcResult res = mockMvc.perform(post("/api/v1/boats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Argo\",\"description\":\"trireme\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.id").value(BOAT_ID.toString()))
                .andExpect(jsonPath("$.name").value("Argo"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        // ETag is the bare version, weakly-quoted by Spring's MVC eTag() helper.
        String etag = res.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).contains("0");
        // Location ends with the new id.
        assertThat(res.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith("/" + BOAT_ID);
    }

    @Test
    void post_blankName_returns400_problemJsonWithFieldRequired() throws Exception {
        mockMvc.perform(post("/api/v1/boats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        org.hamcrest.Matchers.containsString(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
                .andExpect(jsonPath("$.type").value("https://boatapp.owt.ch/problems/validation"))
                .andExpect(jsonPath("$.instance").value("/api/v1/boats"));
    }

    @Test
    void post_malformedJson_returns400_withMalformedCode() throws Exception {
        mockMvc.perform(post("/api/v1/boats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0].code").value("request.body.malformed"));
    }

    // -- GET (single) ---------------------------------------------------

    @Test
    void get_existing_returns200_withEtagHeader() throws Exception {
        Boat boat = new Boat(BOAT_ID, "Argo", "trireme", OffsetDateTime.now(ZoneOffset.UTC), 4L);
        when(boatApplicationService.getBoat(any(GetBoatQuery.class))).thenReturn(boat);

        mockMvc.perform(get("/api/v1/boats/{id}", BOAT_ID))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.name").value("Argo"))
                .andExpect(jsonPath("$.version").value(4));
    }

    // -- GET (list) -----------------------------------------------------

    @Test
    void list_defaultParams_returns200_withPageEnvelope() throws Exception {
        PageResult<Boat> page = new PageResult<>(List.of(), 0L, 0, 10, 0);
        when(boatApplicationService.listBoats(any(ListBoatsQuery.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/boats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.empty").value(true));
    }

    @Test
    void list_negativePage_returns400_constraintViolation() throws Exception {
        mockMvc.perform(get("/api/v1/boats").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://boatapp.owt.ch/problems/validation"))
                .andExpect(jsonPath("$.messages[0].code").value("field.range.invalid"));
    }

    @Test
    void list_oversizeSize_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/boats").param("size", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0].code").value("field.range.invalid"));
    }

    // -- PUT ------------------------------------------------------------

    @Test
    void put_validBodyAndIfMatch_returns200_withRefreshedEtag() throws Exception {
        when(securityHelper.getCurrentAppUserId()).thenReturn(USER_ID);
        Boat updated = new Boat(BOAT_ID, "Argo II", "rebuilt", OffsetDateTime.now(ZoneOffset.UTC), 5L);
        when(boatApplicationService.updateBoat(any(UpdateBoatCommand.class))).thenReturn(updated);

        mockMvc.perform(put("/api/v1/boats/{id}", BOAT_ID)
                        .header(HttpHeaders.IF_MATCH, "4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Argo II\",\"description\":\"rebuilt\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.version").value(5));
    }

    @Test
    void put_missingIfMatch_returns428() throws Exception {
        mockMvc.perform(put("/api/v1/boats/{id}", BOAT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type")
                        .value("https://boatapp.owt.ch/problems/precondition-required"));
    }

    @Test
    void put_malformedIfMatch_returns400_withFormatInvalid() throws Exception {
        when(securityHelper.getCurrentAppUserId()).thenReturn(USER_ID);

        mockMvc.perform(put("/api/v1/boats/{id}", BOAT_ID)
                        .header(HttpHeaders.IF_MATCH, "not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Argo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0].code").value("field.format.invalid"));
    }

    @Test
    void put_quotedIfMatch_isAccepted() throws Exception {
        when(securityHelper.getCurrentAppUserId()).thenReturn(USER_ID);
        Boat updated = new Boat(BOAT_ID, "Argo", "x", OffsetDateTime.now(ZoneOffset.UTC), 5L);
        when(boatApplicationService.updateBoat(any(UpdateBoatCommand.class))).thenReturn(updated);

        // The contract requires a bare integer; the controller is defensive
        // and strips a surrounding pair of double quotes (RFC 7232 form).
        mockMvc.perform(put("/api/v1/boats/{id}", BOAT_ID)
                        .header(HttpHeaders.IF_MATCH, "\"4\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Argo\"}"))
                .andExpect(status().isOk());
    }

    // -- DELETE ---------------------------------------------------------

    @Test
    void delete_returns204() throws Exception {
        when(securityHelper.getCurrentAppUserId()).thenReturn(USER_ID);

        mockMvc.perform(delete("/api/v1/boats/{id}", BOAT_ID))
                .andExpect(status().isNoContent());

        verify(boatApplicationService).deleteBoat(any(DeleteBoatCommand.class));
    }
}
