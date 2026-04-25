package ch.owt.boatapp.infrastructure.security;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Helper that bridges the Spring Security {@code Authentication} (a JWT
 * principal in non-dev, a dummy in dev) to the domain {@code AppUser}.
 *
 * <p>Hides the JWT-claim parsing from the application layer: callers ask
 * for "the current user" and get a domain {@code AppUser.id} back.
 *
 * <p><strong>Step 02a3 minimal body.</strong> Returns the {@link #DEV_USER_ID}
 * unconditionally — the JWT-claim resolution and {@code GetUserUseCase.syncUser}
 * call land in step 02a4 alongside the {@code SecurityFilterChain}. The same
 * UUID is seeded as the dummy {@code AppUser} by {@code DevSecurityConfig} in
 * 02a4, so the {@code boat_audit.performed_by_user_id} foreign key resolves
 * end-to-end in dev mode.
 */
@Component
public class SecurityHelper {

    /**
     * UUID of the dummy {@code AppUser} used in dev mode. Kept in sync with
     * the seed insert added by {@code DevSecurityConfig} in step 02a4.
     */
    public static final UUID DEV_USER_ID =
            UUID.fromString("99999999-9999-9999-9999-999999999999");

    /**
     * Resolve the {@code AppUser.id} of the currently authenticated user.
     *
     * <p>02a3 returns {@link #DEV_USER_ID} unconditionally. 02a4 will read
     * the JWT {@code sub} claim from the {@code SecurityContext} and call
     * {@code GetUserUseCase.syncUser(...)} to upsert the corresponding
     * {@code AppUser} row before returning its id.
     *
     * @return the current user's {@code AppUser.id} (UUID); never {@code null}
     */
    public UUID getCurrentAppUserId() {
        return DEV_USER_ID;
    }
}
