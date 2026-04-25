package ch.owt.boatapp.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pure-Java immutable domain record for an authenticated application user,
 * synced from the JWT claims (sub, preferred_username, email, given_name,
 * family_name) on every request.
 *
 * <p>Lives in {@code domain.model} — no Spring, no Jakarta. The
 * {@code keycloakId} field stores the JWT {@code sub} claim and is the
 * upsert key. Refresh flows rebuild a fresh instance carrying over
 * {@code id}, {@code keycloakId} and {@code firstLogin}.
 *
 * @param id         unique identifier (assigned by the domain on first sync)
 * @param keycloakId JWT {@code sub} claim — the upsert key
 * @param username   {@code preferred_username} claim
 * @param email      {@code email} claim
 * @param firstName  {@code given_name} claim, may be {@code null}
 * @param lastName   {@code family_name} claim, may be {@code null}
 * @param firstLogin timestamp of the user's first observed login
 * @param lastLogin  timestamp of the user's most recent login
 */
public record AppUser(UUID id, String keycloakId, String username, String email,
                      String firstName, String lastName,
                      OffsetDateTime firstLogin, OffsetDateTime lastLogin) {
}
