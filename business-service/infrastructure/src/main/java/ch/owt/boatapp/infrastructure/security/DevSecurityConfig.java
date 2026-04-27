package ch.owt.boatapp.infrastructure.security;

import ch.owt.boatapp.application.port.in.GetUserUseCase;
import ch.owt.boatapp.application.port.in.SyncUserCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Arrays;
import java.util.Set;

/**
 * Business Service dev-profile security configuration.
 *
 * <p>Bypasses Keycloak entirely: the filter chain {@code permitAll}s every
 * request, no JWT validation runs and no session is created. On
 * {@link ApplicationReadyEvent} a dummy {@code AppUser} keyed by
 * {@link DevSecurityHelper#DEV_KEYCLOAK_ID} is upserted via
 * {@link GetUserUseCase#syncUser(SyncUserCommand)} so the
 * {@code boat_audit.performed_by_user_id} foreign key resolves on every dev
 * write — without standing up a Keycloak realm.
 *
 * <p>Active only when the {@code dev} profile is set. A clear
 * {@code WARN}-level banner is logged on startup so dev-only behaviour is
 * obvious in the build log.
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(DevSecurityConfig.class);

    /**
     * Profiles that MUST NOT be combined with {@code dev}. If any of these is
     * also active, {@link #bootstrapDummyUser} fails fast — see the rationale
     * in that method's Javadoc.
     */
    private static final Set<String> FORBIDDEN_COMPANION_PROFILES =
            Set.of("local-intg", "staging", "prod");

    private final GetUserUseCase getUserUseCase;

    /**
     * @param getUserUseCase inbound use-case used to seed the dummy
     *                       {@code AppUser} on startup
     */
    public DevSecurityConfig(GetUserUseCase getUserUseCase) {
        this.getUserUseCase = getUserUseCase;
    }

    /**
     * Build the permit-all filter chain used in dev mode.
     *
     * @param http Spring's filter-chain builder
     * @return the configured {@link SecurityFilterChain} bean
     * @throws Exception if the underlying configurers reject the configuration
     *                   (Spring's API contract; not thrown in practice)
     */
    @Bean
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * Seed the dummy {@code AppUser} once the application context is ready.
     *
     * <p>{@link ApplicationReadyEvent} fires after the embedded server is
     * accepting requests, so by the time any HTTP traffic arrives the upsert
     * has completed and {@link DevSecurityHelper#getCurrentAppUserId()}
     * resolves cleanly.
     *
     * <p><strong>Defense in depth.</strong> The {@code dev} profile bypasses
     * authentication entirely. To make a misconfigured deployment (e.g.
     * {@code SPRING_PROFILES_ACTIVE=dev,prod}) fail fast rather than silently
     * exposing every endpoint anonymously, this listener throws when any of
     * {@link #FORBIDDEN_COMPANION_PROFILES} is also active. The check runs
     * before the dummy-user upsert so the side effect is not visible until
     * the profile combination is verified safe.
     *
     * @param event ready-event that exposes the {@link Environment} so the
     *              active-profile set can be inspected
     * @throws IllegalStateException if {@code dev} is combined with a
     *         non-dev profile
     */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapDummyUser(ApplicationReadyEvent event) {
        Set<String> activeProfiles = Set.copyOf(
                Arrays.asList(event.getApplicationContext().getEnvironment().getActiveProfiles()));
        for (String forbidden : FORBIDDEN_COMPANION_PROFILES) {
            if (activeProfiles.contains(forbidden)) {
                throw new IllegalStateException(
                        "DEV profile must not be combined with non-dev profiles. Active="
                                + activeProfiles + "; forbidden=" + FORBIDDEN_COMPANION_PROFILES);
            }
        }
        log.warn("!! Running in DEV mode -- authentication bypassed !!");
        getUserUseCase.syncUser(new SyncUserCommand(
                DevSecurityHelper.DEV_KEYCLOAK_ID,
                "developer",
                "dev@localhost",
                "Dev",
                "User"));
    }
}
