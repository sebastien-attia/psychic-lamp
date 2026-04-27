package ch.owt.boatapp.infrastructure.security;

import ch.owt.boatapp.domain.model.AppUser;
import ch.owt.boatapp.application.port.out.AppUserRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Dev-profile {@link SecurityHelper} that returns the id of the dummy
 * {@code AppUser} seeded on startup by {@code DevSecurityConfig}.
 *
 * <p>The dummy user is keyed by {@link #DEV_KEYCLOAK_ID}. After the first
 * lookup we cache the resulting UUID — the dummy user is created once on
 * {@code ApplicationReadyEvent} and never deleted in dev mode, so the cache
 * is safe.
 */
@Component
@Profile("dev")
public class DevSecurityHelper implements SecurityHelper {

    /**
     * Stable {@code keycloakId} used for the dev dummy {@code AppUser}. The
     * value is a sentinel string (not a Keycloak {@code sub} UUID) — there
     * is no Keycloak in dev mode, so collisions with real users are impossible.
     */
    public static final String DEV_KEYCLOAK_ID = "dev-user";

    private final AppUserRepositoryPort appUserRepository;
    private volatile UUID cachedId;

    /**
     * @param appUserRepository outbound port used to look up the dummy user
     *                          seeded by {@code DevSecurityConfig}
     */
    public DevSecurityHelper(AppUserRepositoryPort appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the cached dummy-user UUID, looking it up on first call.
     *
     * @throws IllegalStateException if {@code DevSecurityConfig.bootstrapDummyUser}
     *         has not yet completed (request arrived before
     *         {@code ApplicationReadyEvent})
     */
    @Override
    public UUID getCurrentAppUserId() {
        UUID id = cachedId;
        if (id != null) {
            return id;
        }
        // Benign race: at worst N threads each do one repository read on
        // cold start. UUID is immutable and {@code cachedId} is volatile,
        // so publication is safe; the duplicate reads only burn the dev
        // profile's already-trivial startup budget.
        AppUser user = appUserRepository.findByKeycloakId(DEV_KEYCLOAK_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "Dev dummy AppUser not found for keycloakId="
                                + DEV_KEYCLOAK_ID
                                + " — DevSecurityConfig bootstrap may have failed"));
        cachedId = user.id();
        return cachedId;
    }
}
