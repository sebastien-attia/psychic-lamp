package ch.owt.boatapp.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the compact-constructor invariants of {@link BoatId}: rejects
 * {@code null} and the nil UUID. Both rules are defense-in-depth on top of
 * Bean Validation at the REST adapter — they fire even when a non-REST
 * caller (test, CLI, future queue consumer) bypasses the HTTP gate.
 */
class BoatIdTest {

    /** A non-nil UUID is accepted and exposed verbatim by {@code value()}. */
    @Test
    void constructor_acceptsNonNilUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(new BoatId(uuid).value()).isEqualTo(uuid);
    }

    /** {@code null} is rejected with {@link IllegalArgumentException}. */
    @Test
    void constructor_rejectsNull() {
        assertThatThrownBy(() -> new BoatId(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    /** The all-zero UUID is rejected (catches uninitialised value bugs). */
    @Test
    void constructor_rejectsNilUuid() {
        assertThatThrownBy(() -> new BoatId(new UUID(0L, 0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nil UUID");
    }

    /** Records implement value-based equality on the wrapped UUID. */
    @Test
    void recordEquality_isValueBased() {
        UUID uuid = UUID.randomUUID();
        assertThat(new BoatId(uuid)).isEqualTo(new BoatId(uuid));
        assertThat(new BoatId(uuid)).hasSameHashCodeAs(new BoatId(uuid));
    }
}
