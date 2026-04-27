package ch.owt.boatapp.application.port.in;

import ch.owt.boatapp.domain.model.AppUser;

/**
 * Inbound port for resolving the current {@link AppUser} from JWT claims.
 *
 * <p>Pure-Java interface. The web adapter (added in step 02a4) calls
 * {@link #syncUser(SyncUserCommand)} on every authenticated request to keep
 * the {@code APP_USER} table in sync with the JWT, and uses
 * {@link #getUserByKeycloakId(String)} for read-only lookups.
 */
public interface GetUserUseCase {

    /**
     * Upsert the {@link AppUser} keyed by {@code keycloakId} with the latest
     * claim values, creating it on first sync and updating
     * {@code lastLogin} on every subsequent call.
     *
     * @param command the JWT-claim values to sync
     * @return the synced {@link AppUser}
     */
    AppUser syncUser(SyncUserCommand command);

    /**
     * Look up an {@link AppUser} by their {@code keycloakId} (JWT {@code sub}
     * claim).
     *
     * @param keycloakId JWT {@code sub} claim
     * @return the matching user
     * @throws IllegalStateException if no matching user exists; callers should
     *                               sync first via {@link #syncUser}
     */
    AppUser getUserByKeycloakId(String keycloakId);
}
