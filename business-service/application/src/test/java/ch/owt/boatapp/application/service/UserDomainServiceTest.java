package ch.owt.boatapp.application.service;

import ch.owt.boatapp.application.port.in.SyncUserCommand;
import ch.owt.boatapp.application.port.out.AppUserRepositoryPort;
import ch.owt.boatapp.domain.model.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Java unit tests for {@link UserDomainService}: pins the sync (upsert)
 * contract — first call inserts a fresh {@link AppUser} with
 * {@code firstLogin == lastLogin}; subsequent calls preserve the original
 * id, keycloakId and firstLogin while refreshing the mutable claim fields
 * and bumping lastLogin.
 */
@ExtendWith(MockitoExtension.class)
class UserDomainServiceTest {

    @Mock private AppUserRepositoryPort appUserRepository;

    private final Instant initial = Instant.parse("2026-04-26T10:00:00Z");
    private final Clock fixedClock = Clock.fixed(initial, ZoneOffset.UTC);

    private UserDomainService service;

    @BeforeEach
    void setUp() {
        service = new UserDomainService(appUserRepository, fixedClock);
    }

    /** First sync (no existing row): insert with firstLogin == lastLogin == now. */
    @Test
    void syncUser_firstCall_insertsWithMatchingFirstAndLastLogin() {
        when(appUserRepository.findByKeycloakId("kc-sub-1")).thenReturn(Optional.empty());
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        SyncUserCommand cmd = new SyncUserCommand("kc-sub-1", "alice", "alice@x.test", "Alice", "A");
        AppUser saved = service.syncUser(cmd);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(captor.capture());
        AppUser persisted = captor.getValue();
        assertThat(persisted.id()).isNotNull();
        assertThat(persisted.keycloakId()).isEqualTo("kc-sub-1");
        assertThat(persisted.username()).isEqualTo("alice");
        assertThat(persisted.email()).isEqualTo("alice@x.test");
        assertThat(persisted.firstName()).isEqualTo("Alice");
        assertThat(persisted.lastName()).isEqualTo("A");
        assertThat(persisted.firstLogin())
                .isEqualTo(persisted.lastLogin())
                .isEqualTo(OffsetDateTime.ofInstant(initial, ZoneOffset.UTC));
        assertThat(saved).isSameAs(persisted);
    }

    /** Second sync (existing row): keep id, keycloakId, firstLogin; refresh claims; bump lastLogin. */
    @Test
    void syncUser_existingRow_preservesIdAndFirstLogin_refreshesClaimsAndLastLogin() {
        UUID existingId = UUID.randomUUID();
        OffsetDateTime originalFirstLogin = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        AppUser existing = new AppUser(
                existingId, "kc-sub-1", "alice", "alice@x.test",
                "Alice", "A", originalFirstLogin, originalFirstLogin);
        when(appUserRepository.findByKeycloakId("kc-sub-1")).thenReturn(Optional.of(existing));
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        // Updated claims arriving from the new JWT
        SyncUserCommand cmd = new SyncUserCommand(
                "kc-sub-1", "alice-renamed", "alice@new.test", "Ali", "Anderson");
        AppUser refreshed = service.syncUser(cmd);

        assertThat(refreshed.id()).isEqualTo(existingId);
        assertThat(refreshed.keycloakId()).isEqualTo("kc-sub-1");
        assertThat(refreshed.firstLogin()).isEqualTo(originalFirstLogin);
        assertThat(refreshed.lastLogin())
                .isEqualTo(OffsetDateTime.ofInstant(initial, ZoneOffset.UTC))
                .isAfter(originalFirstLogin);
        assertThat(refreshed.username()).isEqualTo("alice-renamed");
        assertThat(refreshed.email()).isEqualTo("alice@new.test");
        assertThat(refreshed.firstName()).isEqualTo("Ali");
        assertThat(refreshed.lastName()).isEqualTo("Anderson");
    }

    /** {@code getUserByKeycloakId} hit → returns the row. */
    @Test
    void getUserByKeycloakId_hit_returnsRow() {
        // Use the fixed clock — every other test in this class threads it
        // through, so this one stays consistent and never depends on wall time.
        OffsetDateTime now = OffsetDateTime.ofInstant(initial, ZoneOffset.UTC);
        AppUser existing = new AppUser(
                UUID.randomUUID(), "kc-sub-2", "bob", "bob@x.test",
                null, null, now, now);
        when(appUserRepository.findByKeycloakId("kc-sub-2")).thenReturn(Optional.of(existing));

        assertThat(service.getUserByKeycloakId("kc-sub-2")).isSameAs(existing);
    }

    /** {@code getUserByKeycloakId} miss → IllegalStateException naming the missing key. */
    @Test
    void getUserByKeycloakId_miss_throwsIllegalState() {
        when(appUserRepository.findByKeycloakId("kc-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUserByKeycloakId("kc-missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kc-missing")
                .hasMessageContaining("syncUser");
    }
}
