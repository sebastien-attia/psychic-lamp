package ch.owt.boatapp.infrastructure.security;

import org.springframework.stereotype.Component;

/**
 * Helper that bridges the Spring Security {@code Authentication} (a JWT
 * principal in non-dev, a dummy in dev) to the domain {@code AppUser}.
 *
 * <p>Hides the JWT-claim parsing from the application layer: callers ask
 * for "the current user" and get a domain object back. Method bodies are
 * added in step 02a4.
 */
@Component
public class SecurityHelper {
}
