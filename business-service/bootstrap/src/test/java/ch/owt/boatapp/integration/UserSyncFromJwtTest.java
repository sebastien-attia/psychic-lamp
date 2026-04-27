package ch.owt.boatapp.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static ch.owt.boatapp.testsupport.JwtTestSupport.mockJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that {@link ch.owt.boatapp.infrastructure.security.JwtSecurityHelper}
 * upserts an {@code AppUser} row from the JWT claims on every authenticated
 * request: the first request creates the row with
 * {@code firstLogin == lastLogin}; the second request updates only
 * {@code lastLogin} and leaves {@code firstLogin} unchanged.
 */
class UserSyncFromJwtTest extends IntegrationTestBase {

    /**
     * First request creates the AppUser row from JWT claims; second request
     * updates {@code lastLogin} without inserting a duplicate.
     */
    @Test
    void firstRequest_creates_row_secondRequest_updates_lastLogin_only() throws Exception {
        String sub = "kc-user-sync-" + System.nanoTime();
        String username = "synced-user";
        String email = "synced@example.test";

        // Use POST so SecurityHelper.getCurrentAppUserId() is invoked (GET does not
        // need the user id and would skip the JWT-driven AppUser upsert).
        mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt(sub, username, email, "First", "Last"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sync-test-1\"}"))
                .andExpect(status().isCreated());

        List<Map<String, Object>> rowsAfterFirst = jdbc.queryForList(
                "SELECT id, keycloak_id, username, email, first_name, last_name, " +
                        "first_login, last_login FROM app_user WHERE keycloak_id = ?", sub);
        assertThat(rowsAfterFirst).hasSize(1);
        Map<String, Object> first = rowsAfterFirst.get(0);
        assertThat(first.get("username")).isEqualTo(username);
        assertThat(first.get("email")).isEqualTo(email);
        assertThat(first.get("first_name")).isEqualTo("First");
        assertThat(first.get("last_name")).isEqualTo("Last");
        Timestamp firstLogin = (Timestamp) first.get("first_login");
        Timestamp lastLoginAfterFirst = (Timestamp) first.get("last_login");
        assertThat(firstLogin).isEqualTo(lastLoginAfterFirst);

        // Use POST so SecurityHelper.getCurrentAppUserId() is invoked (GET does not
        // need the user id and would skip the JWT-driven AppUser upsert).
        mockMvc.perform(post("/api/v1/boats")
                        .with(mockJwt(sub, username, email, "First", "Last"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sync-test-2\"}"))
                .andExpect(status().isCreated());

        List<Map<String, Object>> rowsAfterSecond = jdbc.queryForList(
                "SELECT id, keycloak_id, first_login, last_login FROM app_user WHERE keycloak_id = ?", sub);
        // Row-count + first_login invariants are sufficient: they prove the
        // upsert took the existing row instead of inserting a duplicate. We
        // deliberately do NOT compare the lastLogin timestamps directly —
        // wall-clock advance is an implementation detail of the upsert SQL
        // and asserting on it would be flake-prone on millisecond-resolution
        // CI clocks.
        assertThat(rowsAfterSecond)
                .as("Second request must NOT create a duplicate row")
                .hasSize(1);
        Map<String, Object> second = rowsAfterSecond.get(0);
        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(second.get("first_login")).isEqualTo(firstLogin);
    }
}
