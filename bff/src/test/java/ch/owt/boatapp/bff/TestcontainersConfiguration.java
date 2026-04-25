package ch.owt.boatapp.bff;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Test-only Spring configuration that exposes a shared PostgreSQL
 * Testcontainer wired into Spring Boot via {@code @ServiceConnection}.
 *
 * <p>Imported by {@link ch.owt.boatapp.bff.BffApplicationTests} and by every
 * BFF integration-test base class. Public so test classes in sibling
 * packages can {@code @Import} it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/**
	 * @return a PostgreSQL Testcontainer auto-bound as the application's
	 *         datasource for the duration of the test JVM
	 */
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
	}

}
