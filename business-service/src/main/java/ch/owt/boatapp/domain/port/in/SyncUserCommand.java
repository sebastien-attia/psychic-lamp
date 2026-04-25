package ch.owt.boatapp.domain.port.in;

/**
 * Command record carrying the JWT-claim values needed to sync (upsert) the
 * domain {@link ch.owt.boatapp.domain.model.AppUser}.
 *
 * <p>Pure-Java record. The web adapter (added in step 02a4) extracts these
 * from the validated JWT on every authenticated request and feeds them to
 * {@link GetUserUseCase#syncUser(SyncUserCommand)}. The {@code keycloakId}
 * is the JWT {@code sub} claim and acts as the upsert key.
 *
 * @param keycloakId JWT {@code sub} claim — upsert key
 * @param username   {@code preferred_username} claim
 * @param email      {@code email} claim
 * @param firstName  {@code given_name} claim, may be {@code null}
 * @param lastName   {@code family_name} claim, may be {@code null}
 */
public record SyncUserCommand(String keycloakId, String username, String email,
                              String firstName, String lastName) {
}
