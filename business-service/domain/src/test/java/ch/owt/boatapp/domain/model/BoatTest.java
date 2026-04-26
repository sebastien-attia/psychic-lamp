package ch.owt.boatapp.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract of {@link Boat}: it is a plain value record. The compact
 * constructor stays empty by design (length / nullability are enforced by
 * {@code SyntacticValidator} and {@code SemanticValidator} before construction
 * — see the Javadoc on {@link Boat}). These tests therefore focus on
 * value-record semantics: accessor pass-through, equality, hashing, and
 * tolerance of the {@code description} / {@code version} nullable contract.
 */
class BoatTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);

    /** Accessors expose every component verbatim. */
    @Test
    void accessors_returnConstructorArguments() {
        Boat boat = new Boat(ID, "Black Pearl", "A pirate ship", CREATED_AT, 7L);

        assertThat(boat.id()).isEqualTo(ID);
        assertThat(boat.name()).isEqualTo("Black Pearl");
        assertThat(boat.description()).isEqualTo("A pirate ship");
        assertThat(boat.createdAt()).isEqualTo(CREATED_AT);
        assertThat(boat.version()).isEqualTo(7L);
    }

    /** {@code description} and {@code version} are explicitly nullable per Javadoc. */
    @Test
    void nullDescriptionAndVersion_areAllowed() {
        Boat boat = new Boat(ID, "Argo", null, CREATED_AT, null);

        assertThat(boat.description()).isNull();
        assertThat(boat.version()).isNull();
    }

    /** Records implement value-based equality across every component. */
    @Test
    void recordEquality_isValueBased() {
        Boat a = new Boat(ID, "n", "d", CREATED_AT, 1L);
        Boat b = new Boat(ID, "n", "d", CREATED_AT, 1L);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    /** Differing version → not equal (relevant for optimistic-lock comparisons). */
    @Test
    void recordEquality_differsOnVersion() {
        Boat v0 = new Boat(ID, "n", "d", CREATED_AT, 0L);
        Boat v1 = new Boat(ID, "n", "d", CREATED_AT, 1L);

        assertThat(v0).isNotEqualTo(v1);
    }
}
