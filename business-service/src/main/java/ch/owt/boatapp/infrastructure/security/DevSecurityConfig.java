package ch.owt.boatapp.infrastructure.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Business Service dev-profile security configuration.
 *
 * <p>Bypasses Keycloak: the filter chain {@code permitAll()}s every request
 * and a dummy {@code AppUser} is auto-created on startup so persistence and
 * audit FKs still resolve. Used for fast iteration without bringing up
 * Keycloak.
 *
 * <p>The {@code SecurityFilterChain} bean and the dummy-user bootstrap are
 * added in step 02a4.
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {
}
