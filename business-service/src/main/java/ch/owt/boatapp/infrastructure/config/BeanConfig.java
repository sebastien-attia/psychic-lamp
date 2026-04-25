package ch.owt.boatapp.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Wires the pure-Java domain services as Spring beans, injecting their
 * outbound-port collaborators.
 *
 * <p>The domain itself carries no Spring annotations — this is the only
 * place where {@code BoatDomainService} and {@code UserDomainService} become
 * managed components. Bean methods are added in step 02a3.
 */
@Configuration
public class BeanConfig {
}
