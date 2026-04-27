package ch.owt.boatapp.integration;

import ch.owt.boatapp.BusinessServiceApplication;
import ch.owt.boatapp.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Base class for Business Service Spring Boot integration tests.
 *
 * <p>Boots the full application context against a shared PostgreSQL
 * Testcontainer (declared by {@link TestcontainersConfiguration}, wired via
 * {@code @ServiceConnection}). Runs with no active Spring profile so the
 * production {@link ch.owt.boatapp.infrastructure.security.ResourceServerSecurityConfig}
 * is in effect — tests authenticate via
 * {@link ch.owt.boatapp.testsupport.JwtTestSupport#mockJwt()} which injects a
 * {@code JwtAuthenticationToken} directly into the {@code SecurityContext}
 * and bypasses the real {@code JwtDecoder}.
 *
 * <p>{@code @BeforeEach} truncates the boat / audit / user tables so each
 * test starts from a clean state. Truncation (rather than a transactional
 * rollback) is required because the optimistic-locking and audit tests need
 * commits to actually happen between requests.
 */
// `classes` is explicit because the infrastructure module's test-jar puts
// InfrastructureTestApplication on this module's test classpath (Maven reactor
// shares test-classes when a test-jar dep is declared). Without an explicit
// pointer, Spring Boot's auto-discovery would find both @SpringBootConfiguration
// classes and refuse to choose.
@SpringBootTest(classes = BusinessServiceApplication.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * Truncate {@code boat_audit}, {@code boats} and {@code app_user} (in
     * FK order) before every test. {@code CASCADE} handles any future FK
     * chain; {@code RESTART IDENTITY} keeps audit-id sequences deterministic
     * across tests in the same JVM. Future test additions that introduce a
     * new table must extend this list rather than rely on {@code CASCADE} to
     * cover them, so the truncation order remains explicit.
     */
    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE TABLE boat_audit, boats, app_user RESTART IDENTITY CASCADE");
    }
}
