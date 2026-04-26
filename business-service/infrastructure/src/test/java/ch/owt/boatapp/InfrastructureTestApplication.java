package ch.owt.boatapp;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Test-only Spring Boot configuration root for the infrastructure module.
 *
 * <p>{@code @WebMvcTest} and {@code @DataJpaTest} slice tests bootstrap by
 * walking up the package tree from the test class until they find a
 * {@code @SpringBootConfiguration}. The actual {@code BusinessServiceApplication}
 * lives in the bootstrap module and is intentionally NOT on the
 * infrastructure module's classpath (one-way Maven dependency direction);
 * this lightweight stand-in fills the gap so the slices can wire up.
 *
 * <p>Deliberately minimal:
 * <ul>
 *   <li>No {@code @ComponentScan} — each slice test pulls in the adapter
 *       beans it needs via explicit {@code @Import(...)}. This prevents the
 *       slice from accidentally activating the security / controller / JPA
 *       layer it does not own.</li>
 *   <li>No {@code @EnableJpaRepositories} or {@code @EntityScan} — Spring
 *       Boot's auto-configuration package mechanism (implicit with
 *       {@code @EnableAutoConfiguration} on a class at the {@code ch.owt.boatapp}
 *       root) discovers the entity types and Spring Data repositories
 *       automatically. Adding {@code @EnableJpaRepositories} explicitly
 *       triggers JPA wiring even when {@code @WebMvcTest} excludes the JPA
 *       auto-config — which makes the {@code @WebMvcTest} slice fail to load.</li>
 * </ul>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class InfrastructureTestApplication {
}
