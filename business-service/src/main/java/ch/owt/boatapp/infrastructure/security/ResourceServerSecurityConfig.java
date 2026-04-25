package ch.owt.boatapp.infrastructure.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Business Service security configuration for non-dev profiles.
 *
 * <p>Configures the service as a stateless OAuth2 resource server: every
 * request must carry a {@code Bearer} JWT issued by Keycloak; the JWKS is
 * fetched from {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}.
 * No session, no CSRF.
 *
 * <p>The {@code SecurityFilterChain} bean and JWT decoder customizations
 * are added in step 02a4.
 */
@Configuration
@Profile("!dev")
public class ResourceServerSecurityConfig {
}
