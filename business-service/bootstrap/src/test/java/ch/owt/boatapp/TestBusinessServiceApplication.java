package ch.owt.boatapp;

import ch.owt.boatapp.testsupport.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * Convenience entry point for running the full Business Service against the
 * shared Postgres Testcontainer from a local IDE — analogous to Spring
 * Initializr's generated TestApplication. Production startup is via
 * {@link BusinessServiceApplication#main(String[])}; this class layers in the
 * test-jar's {@link TestcontainersConfiguration} so the embedded run uses a
 * Postgres container instead of expecting an external one.
 */
public class TestBusinessServiceApplication {

    /**
     * Boot the application with the Testcontainers Postgres bean wired in.
     *
     * @param args standard Spring Boot CLI args, forwarded verbatim
     */
    public static void main(String[] args) {
        SpringApplication.from(BusinessServiceApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
