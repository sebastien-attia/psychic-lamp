package ch.owt.boatapp.infrastructure.security;

import java.util.UUID;

/**
 * Resolves the {@code AppUser.id} of the currently authenticated user for the
 * {@code adapter.in.web} layer.
 *
 * <p>Two profile-keyed implementations are wired:
 * <ul>
 *   <li>{@link JwtSecurityHelper} ({@code @Profile("!dev")}) reads the
 *       {@code JwtAuthenticationToken} from the {@code SecurityContext} and
 *       upserts the {@code AppUser} via {@code GetUserUseCase.syncUser(...)}
 *       on every request — the {@code APP_USER} table stays in sync with the
 *       JWT claims.</li>
 *   <li>{@link DevSecurityHelper} ({@code @Profile("dev")}) returns the id of
 *       the dummy {@code AppUser} seeded by {@code DevSecurityConfig} on
 *       {@code ApplicationReadyEvent}.</li>
 * </ul>
 *
 * <p>Callers depend on this interface only — the concrete implementation is
 * selected by Spring at startup based on the active profile.
 */
public interface SecurityHelper {

    /**
     * Resolve the current authenticated user's {@code AppUser.id}.
     *
     * @return the current user's id; never {@code null}
     * @throws IllegalStateException if no authenticated principal is available
     *                               (non-dev) or if the dev dummy user has not
     *                               been seeded yet (dev)
     */
    UUID getCurrentAppUserId();
}
