package ch.owt.boatapp.integration;

import ch.owt.boatapp.BusinessServiceApplication;
import ch.owt.boatapp.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the {@code dev} profile: authentication is fully bypassed (no
 * Bearer token required) and a dummy {@code AppUser} is auto-created on
 * startup via {@code @EventListener(ApplicationReadyEvent)}. Audit rows for
 * actions performed in dev mode reference the dummy user.
 *
 * <p>Boots its own application context (so {@code @ActiveProfiles("dev")}
 * activates {@code DevSecurityConfig}) — therefore does NOT extend
 * {@link IntegrationTestBase} which deliberately runs without a profile.
 */
// `classes` is explicit because the infrastructure test-jar's
// InfrastructureTestApplication also carries @SpringBootConfiguration —
// see IntegrationTestBase for the same explanation.
@SpringBootTest(classes = BusinessServiceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class DevModeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** The dummy user is created at startup with keycloakId {@code dev-user}. */
    @Test
    void dummyUser_isAutoCreated_onStartup() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, keycloak_id, username FROM app_user WHERE keycloak_id = 'dev-user'");
        assertThat(rows)
                .as("DevSecurityConfig.bootstrapDummyUser must create one row with keycloak_id='dev-user'")
                .hasSize(1);
        assertThat(rows.get(0).get("username")).isNotNull();
    }

    /** GET works without any authentication. */
    @Test
    void get_works_withoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/boats"))
                .andExpect(status().isOk());
    }

    /**
     * POST works without any authentication AND the resulting audit row
     * references the dummy user's id.
     */
    @Test
    void post_works_withoutAuth_andAuditRowReferencesDummyUser() throws Exception {
        mockMvc.perform(post("/api/v1/boats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DevBoat\",\"description\":\"x\"}"))
                .andExpect(status().isCreated());

        Object dummyId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE keycloak_id = 'dev-user'", Object.class);
        Object auditedUserId = jdbc.queryForObject(
                "SELECT performed_by_user_id FROM boat_audit ORDER BY id DESC LIMIT 1", Object.class);
        assertThat(auditedUserId).isEqualTo(dummyId);
    }
}
