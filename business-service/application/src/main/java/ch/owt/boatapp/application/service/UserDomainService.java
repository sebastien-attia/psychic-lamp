package ch.owt.boatapp.application.service;

import ch.owt.boatapp.domain.model.AppUser;
import ch.owt.boatapp.application.port.in.GetUserUseCase;
import ch.owt.boatapp.application.port.in.SyncUserCommand;
import ch.owt.boatapp.application.port.out.AppUserRepositoryPort;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pure-Java implementation of {@link GetUserUseCase}.
 *
 * <p>Wired by {@code BeanConfig}; no Spring annotations. Performs an upsert
 * keyed on {@code keycloakId}: on first sync, creates a new {@link AppUser}
 * with both {@code firstLogin} and {@code lastLogin} set to "now"; on
 * subsequent syncs, rebuilds the record carrying over {@code id},
 * {@code keycloakId} and {@code firstLogin} while refreshing the mutable
 * claim fields and bumping {@code lastLogin}.
 */
public final class UserDomainService implements GetUserUseCase {

    private final AppUserRepositoryPort appUserRepository;
    private final Clock clock;

    /**
     * @param appUserRepository outbound port for {@link AppUser} persistence
     * @param clock             clock used for {@code firstLogin} /
     *                          {@code lastLogin} timestamps (UTC in production)
     */
    public UserDomainService(AppUserRepositoryPort appUserRepository, Clock clock) {
        this.appUserRepository = appUserRepository;
        this.clock = clock;
    }

    @Override
    public AppUser syncUser(SyncUserCommand command) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return appUserRepository.findByKeycloakId(command.keycloakId())
                .map(existing -> refresh(existing, command, now))
                .orElseGet(() -> create(command, now));
    }

    @Override
    public AppUser getUserByKeycloakId(String keycloakId) {
        return appUserRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new IllegalStateException(
                        "AppUser not found for keycloakId=" + keycloakId
                                + " — call syncUser(...) first"));
    }

    private AppUser create(SyncUserCommand command, OffsetDateTime now) {
        AppUser fresh = new AppUser(
                UUID.randomUUID(),
                command.keycloakId(),
                command.username(),
                command.email(),
                command.firstName(),
                command.lastName(),
                now,
                now
        );
        return appUserRepository.save(fresh);
    }

    private AppUser refresh(AppUser existing, SyncUserCommand command, OffsetDateTime now) {
        // Records are immutable: carry over id, keycloakId and firstLogin from
        // the loaded record; refresh the mutable claim fields and bump lastLogin.
        AppUser refreshed = new AppUser(
                existing.id(),
                existing.keycloakId(),
                command.username(),
                command.email(),
                command.firstName(),
                command.lastName(),
                existing.firstLogin(),
                now
        );
        return appUserRepository.save(refreshed);
    }
}
