package ch.owt.boatapp.infrastructure.security;

import ch.owt.boatapp.domain.model.AppUser;
import ch.owt.boatapp.domain.port.in.GetUserUseCase;
import ch.owt.boatapp.domain.port.in.SyncUserCommand;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Non-dev {@link SecurityHelper} backed by the JWT Bearer token validated by
 * {@code spring-oauth2-resource-server}.
 *
 * <p>Reads the {@link JwtAuthenticationToken} from the current
 * {@code SecurityContext}, builds a {@link SyncUserCommand} from the standard
 * OIDC claims ({@code sub}, {@code preferred_username}, {@code email},
 * {@code given_name}, {@code family_name}) and calls
 * {@link GetUserUseCase#syncUser(SyncUserCommand)} on every authenticated
 * request — the upsert keeps the {@code APP_USER} table in lockstep with
 * Keycloak without a separate provisioning flow.
 *
 * <p>Active when the {@code dev} profile is NOT set; the filter chain in
 * {@code ResourceServerSecurityConfig} guarantees an authenticated
 * {@code JwtAuthenticationToken} is present whenever this helper runs.
 */
@Component
@Profile("!dev")
public class JwtSecurityHelper implements SecurityHelper {

    private final GetUserUseCase getUserUseCase;

    /**
     * @param getUserUseCase inbound use-case that upserts {@code AppUser} from
     *                       JWT claims
     */
    public JwtSecurityHelper(GetUserUseCase getUserUseCase) {
        this.getUserUseCase = getUserUseCase;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Extracts the JWT from the {@code SecurityContext}, syncs the
     * {@code AppUser} keyed on the {@code sub} claim and returns its id.
     *
     * @throws IllegalStateException if no {@link JwtAuthenticationToken} is
     *         present (the filter chain rejects the request before this
     *         point, so this would only fire on a misconfigured chain)
     */
    @Override
    public UUID getCurrentAppUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException(
                    "No JwtAuthenticationToken in SecurityContext — filter chain misconfigured?");
        }
        Jwt jwt = jwtAuth.getToken();
        AppUser user = getUserUseCase.syncUser(new SyncUserCommand(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name")));
        return user.id();
    }
}
