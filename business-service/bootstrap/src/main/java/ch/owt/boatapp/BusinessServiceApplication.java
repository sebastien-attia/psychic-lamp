package ch.owt.boatapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the Business Service.
 *
 * <p>{@code scanBasePackages} is declared explicitly so the multi-module
 * layout works without relying on default-root-package inference: this class
 * lives in {@code ch.owt.boatapp}, but components, JPA entities and Spring
 * Data repositories are split across separate jars (application,
 * infrastructure) under {@code ch.owt.boatapp.*} sub-packages. The
 * {@code @SpringBootApplication} base-package scan covers all of them.
 */
@SpringBootApplication(scanBasePackages = "ch.owt.boatapp")
public class BusinessServiceApplication {

	/**
	 * JVM entry point — boots the Spring application context.
	 *
	 * @param args command-line arguments forwarded to Spring.
	 */
	public static void main(String[] args) {
		SpringApplication.run(BusinessServiceApplication.class, args);
	}

}
