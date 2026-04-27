package ch.owt.boatapp.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the compact-constructor invariants of {@link UserId}: rejects
 * {@code null} and the nil UUID. Mirrors {@link BoatIdTest} so future
 * value-object additions follow the same checklist.
 */
class UserIdTest {

    /** A non-nil UUID is accepted and exposed verbatim by {@code value()}. */
    @Test
    void constructor_acceptsNonNilUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(new UserId(uuid).value()).isEqualTo(uuid);
    }

    /** {@code null} is rejected with {@link IllegalArgumentException}. */
    @Test
    void constructor_rejectsNull() {
        assertThatThrownBy(() -> new UserId(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    /** The all-zero UUID is rejected. */
    @Test
    void constructor_rejectsNilUuid() {
        assertThatThrownBy(() -> new UserId(new UUID(0L, 0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nil UUID");
    }

    /** Records implement value-based equality on the wrapped UUID. */
    @Test
    void recordEquality_isValueBased() {
        UUID uuid = UUID.randomUUID();
        assertThat(new UserId(uuid)).isEqualTo(new UserId(uuid));
        assertThat(new UserId(uuid)).hasSameHashCodeAs(new UserId(uuid));
    }
}
