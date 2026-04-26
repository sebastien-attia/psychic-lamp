package ch.owt.boatapp.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Test-only Spring configuration that exposes a shared PostgreSQL
 * Testcontainer wired into Spring Boot via {@code @ServiceConnection}.
 *
 * <p>Lives in the {@code infrastructure} module's test-jar so both
 * {@code @SpringBootTest} integration tests in {@code bootstrap} and
 * {@code @DataJpaTest} slice tests in {@code infrastructure} share the same
 * container definition (single source of truth, single Postgres image to keep
 * up to date).
 *
 * <p>Public so test classes in sibling packages and downstream modules can
 * {@code @Import} it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * Image tag pinned to the same major as production (see {@code
     * docker-compose.yml} → {@code postgres:17-alpine}). Drift between the
     * test image and the production image is the exact bug Testcontainers is
     * meant to prevent — keep these in lockstep.
     */
    private static final String POSTGRES_IMAGE = "postgres:17-alpine";

    /**
     * @return a PostgreSQL Testcontainer auto-bound as the application's
     *         datasource for the duration of the test JVM
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));
    }
}
