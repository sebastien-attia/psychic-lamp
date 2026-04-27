package ch.owt.boatapp;

import ch.owt.boatapp.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Smoke test: the Spring application context loads end-to-end against the
 * shared Postgres Testcontainer. Inherited from the Spring Initializr
 * bootstrap; runs with no profile so {@code @Profile("!dev")} security beans
 * fire (placeholder JWKS URI in {@code src/test/resources/application.yml}).
 */
@Import(TestcontainersConfiguration.class)
// `classes` is explicit because the infrastructure module's test-jar puts
// InfrastructureTestApplication on this module's test classpath (Maven reactor
// shares test-classes when a test-jar dep is declared). Without an explicit
// pointer, Spring Boot's auto-discovery would find both @SpringBootConfiguration
// classes and refuse to choose.
@SpringBootTest(classes = BusinessServiceApplication.class)
class BusinessServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
