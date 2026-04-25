package ch.owt.boatapp.domain.service;

import ch.owt.boatapp.domain.model.AppUser;
import ch.owt.boatapp.domain.port.in.GetUserUseCase;
import ch.owt.boatapp.domain.port.in.SyncUserCommand;
import ch.owt.boatapp.domain.port.out.AppUserRepositoryPort;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pure-Java implementation of {@link GetUserUseCase}.
 *
 * <p>Wired by {@code BeanConfig}; no Spring annotations. Performs an upsert
 * keyed on {@code keycloakId}: on first sync, creates a new {@link AppUser}
 * with both {@code firstLogin} and {@code lastLogin} set to "now"; on
 * subsequent syncs, refreshes the mutable claim fields and bumps
 * {@code lastLogin}.
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
        existing.setUsername(command.username());
        existing.setEmail(command.email());
        existing.setFirstName(command.firstName());
        existing.setLastName(command.lastName());
        existing.setLastLogin(now);
        return appUserRepository.save(existing);
    }
}
