package ch.owt.boatapp.adapter.out.persistence.mapper;

import ch.owt.boatapp.adapter.out.persistence.entity.BoatJpaEntity;
import ch.owt.boatapp.domain.model.Boat;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Java unit tests for {@link BoatPersistenceMapper}: pin field-by-field
 * equivalence between the immutable domain record and the mutable JPA entity,
 * including the explicit {@code null} short-circuits in both directions.
 */
class BoatPersistenceMapperTest {

    private final BoatPersistenceMapper mapper = new BoatPersistenceMapper();

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.of(2026, 4, 26, 10, 0, 0, 0, ZoneOffset.UTC);

    /** Entity → Domain: copies every component verbatim. */
    @Test
    void toDomain_copiesEveryField() {
        BoatJpaEntity entity = new BoatJpaEntity(ID, "Argo", "trireme", CREATED_AT, 7L);

        Boat domain = mapper.toDomain(entity);

        assertThat(domain.id()).isEqualTo(ID);
        assertThat(domain.name()).isEqualTo("Argo");
        assertThat(domain.description()).isEqualTo("trireme");
        assertThat(domain.createdAt()).isEqualTo(CREATED_AT);
        assertThat(domain.version()).isEqualTo(7L);
    }

    /** Domain → Entity: copies every component verbatim. */
    @Test
    void toJpaEntity_copiesEveryField() {
        Boat domain = new Boat(ID, "Argo", "trireme", CREATED_AT, 7L);

        BoatJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(ID);
        assertThat(entity.getName()).isEqualTo("Argo");
        assertThat(entity.getDescription()).isEqualTo("trireme");
        assertThat(entity.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(entity.getVersion()).isEqualTo(7L);
    }

    /** Round-trip preserves equality. */
    @Test
    void roundTrip_preservesEquality() {
        Boat original = new Boat(ID, "Argo", "trireme", CREATED_AT, 7L);

        Boat round = mapper.toDomain(mapper.toJpaEntity(original));

        assertThat(round).isEqualTo(original);
    }

    /** Nullable {@code description} and {@code version} round-trip cleanly. */
    @Test
    void roundTrip_handlesNullableFields() {
        Boat original = new Boat(ID, "Argo", null, CREATED_AT, null);

        Boat round = mapper.toDomain(mapper.toJpaEntity(original));

        assertThat(round.description()).isNull();
        assertThat(round.version()).isNull();
        assertThat(round).isEqualTo(original);
    }

    /** {@code toDomain(null)} short-circuits to {@code null} (defensive). */
    @Test
    void toDomain_nullEntity_returnsNull() {
        assertThat(mapper.toDomain(null)).isNull();
    }

    /** {@code toJpaEntity(null)} short-circuits to {@code null} (defensive). */
    @Test
    void toJpaEntity_nullBoat_returnsNull() {
        assertThat(mapper.toJpaEntity(null)).isNull();
    }
}
