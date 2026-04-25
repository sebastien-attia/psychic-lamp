package ch.owt.boatapp.bff.infrastructure.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * BFF dev-profile security configuration.
 *
 * <p>The BFF is not normally started in {@code dev} (the frontend dev proxy
 * talks straight to the Business Service on :8081). When it is launched
 * for ad-hoc debugging, this configuration applies a {@code permitAll}
 * filter chain so no Keycloak round-trip is required.
 *
 * <p>The {@code SecurityFilterChain} bean is added in step 02a4. That step
 * MUST also either exclude {@code OAuth2ClientAutoConfiguration} on the
 * {@code @SpringBootApplication} or provide a stub {@code keycloak} client
 * registration in {@code application-dev.yml} — otherwise Boot's OAuth2
 * client autoconfig fails fast on missing {@code client-id}.
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {
}
