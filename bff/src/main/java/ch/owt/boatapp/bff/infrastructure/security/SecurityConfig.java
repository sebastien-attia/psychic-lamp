package ch.owt.boatapp.bff.infrastructure.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * BFF security configuration for non-dev profiles
 * ({@code local-intg}, {@code staging}, {@code prod}).
 *
 * <p>Wires session-based OAuth2 login against Keycloak, CSRF protection
 * (cookie repository), and stateless access to {@code /.well-known/jwks.json}.
 * The OAuth2 access token is forwarded as a {@code Bearer} header on outbound
 * calls to the Business Service.
 *
 * <p>The {@code SecurityFilterChain} bean and {@code DefaultOAuth2AuthorizedClientManager}
 * wiring are added in step 02a4.
 */
@Configuration
@Profile("!dev")
public class SecurityConfig {
}
