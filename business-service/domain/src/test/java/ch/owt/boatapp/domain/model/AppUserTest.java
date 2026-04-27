package ch.owt.boatapp.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract of {@link AppUser}: a value record built from JWT claims.
 * The compact constructor accepts every component as-is — invariants come from
 * the persistence-side unique constraint on {@code keycloakId} (covered by
 * {@code AppUserRepositoryAdapterTest}). These tests pin accessor pass-through,
 * the {@code firstName}/{@code lastName} nullable contract, and value
 * equality.
 */
class AppUserTest {

    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime LOGIN_AT =
            OffsetDateTime.of(2026, 4, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    /** Accessors expose every component verbatim. */
    @Test
    void accessors_returnConstructorArguments() {
        AppUser user = new AppUser(
                ID, "kc-sub-123", "alice", "alice@example.test",
                "Alice", "Anderson", LOGIN_AT, LOGIN_AT);

        assertThat(user.id()).isEqualTo(ID);
        assertThat(user.keycloakId()).isEqualTo("kc-sub-123");
        assertThat(user.username()).isEqualTo("alice");
        assertThat(user.email()).isEqualTo("alice@example.test");
        assertThat(user.firstName()).isEqualTo("Alice");
        assertThat(user.lastName()).isEqualTo("Anderson");
        assertThat(user.firstLogin()).isEqualTo(LOGIN_AT);
        assertThat(user.lastLogin()).isEqualTo(LOGIN_AT);
    }

    /** {@code firstName} and {@code lastName} are explicitly nullable per Javadoc. */
    @Test
    void nullableNameFields_areAllowed() {
        AppUser user = new AppUser(
                ID, "kc-sub-456", "bob", "bob@example.test",
                null, null, LOGIN_AT, LOGIN_AT);

        assertThat(user.firstName()).isNull();
        assertThat(user.lastName()).isNull();
    }

    /**
     * The "refresh" flow rebuilds an instance carrying the same {@code id},
     * {@code keycloakId}, and {@code firstLogin} but a newer {@code lastLogin}.
     * Two instances built that way must NOT be equal (lastLogin differs) —
     * proves callers cannot mistake a refresh for the original.
     */
    @Test
    void refreshedInstance_isNotEqualToOriginal() {
        OffsetDateTime later = LOGIN_AT.plusHours(1);
        AppUser original = new AppUser(
                ID, "kc-sub-789", "carol", "carol@example.test",
                "Carol", "Carter", LOGIN_AT, LOGIN_AT);
        AppUser refreshed = new AppUser(
                original.id(), original.keycloakId(), original.username(), original.email(),
                original.firstName(), original.lastName(), original.firstLogin(), later);

        assertThat(refreshed).isNotEqualTo(original);
        assertThat(refreshed.firstLogin()).isEqualTo(original.firstLogin());
        assertThat(refreshed.lastLogin()).isAfter(original.lastLogin());
    }
}
