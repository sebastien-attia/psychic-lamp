package ch.owt.boatapp.domain.port.out;

import ch.owt.boatapp.domain.model.AppUser;

import java.util.Optional;

/**
 * Outbound port the domain uses to find or upsert {@link AppUser} records
 * synced from the incoming JWT claims.
 *
 * <p>Implemented in {@code adapter.out.persistence}. The {@code keycloakId}
 * (JWT {@code sub}) is the upsert key.
 */
public interface AppUserRepositoryPort {

    /**
     * Look up a user by their {@code keycloakId} (JWT {@code sub} claim).
     *
     * @param keycloakId JWT {@code sub} claim
     * @return the matching user, or {@link Optional#empty()} if none exists
     */
    Optional<AppUser> findByKeycloakId(String keycloakId);

    /**
     * Persist (insert or update) a user. The persisted state is returned.
     *
     * @param user the user to persist
     * @return the user as it stands after persistence
     */
    AppUser save(AppUser user);
}
