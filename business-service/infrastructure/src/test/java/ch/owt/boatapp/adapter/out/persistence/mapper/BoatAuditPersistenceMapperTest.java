package ch.owt.boatapp.adapter.out.persistence.mapper;

import ch.owt.boatapp.adapter.out.persistence.entity.AppUserJpaEntity;
import ch.owt.boatapp.adapter.out.persistence.entity.BoatAuditJpaEntity;
import ch.owt.boatapp.domain.model.AuditAction;
import ch.owt.boatapp.domain.model.BoatAudit;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Java unit tests for {@link BoatAuditPersistenceMapper}: pin the
 * domain ↔ entity round-trip and the asymmetric {@code performedBy} bridging
 * (UUID on the domain side, lazy {@link AppUserJpaEntity} reference on the
 * persistence side).
 */
class BoatAuditPersistenceMapperTest {

    private final BoatAuditPersistenceMapper mapper = new BoatAuditPersistenceMapper();

    private static final UUID BOAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final OffsetDateTime PERFORMED_AT =
            OffsetDateTime.of(2026, 4, 26, 10, 0, 0, 0, ZoneOffset.UTC);

    /** Entity → Domain: flattens {@code performedBy.getId()} into {@code performedByUserId}. */
    @Test
    void toDomain_flattensPerformedByReference() {
        AppUserJpaEntity userRef = new AppUserJpaEntity(
                USER_ID, "kc-sub", "u", "u@x.test", null, null, PERFORMED_AT, PERFORMED_AT);
        BoatAuditJpaEntity entity = new BoatAuditJpaEntity(
                42L, BOAT_ID, AuditAction.UPDATED, "Argo", "trireme", 3L, userRef, PERFORMED_AT);

        BoatAudit domain = mapper.toDomain(entity);

        assertThat(domain.id()).isEqualTo(42L);
        assertThat(domain.boatId()).isEqualTo(BOAT_ID);
        assertThat(domain.action()).isEqualTo(AuditAction.UPDATED);
        assertThat(domain.name()).isEqualTo("Argo");
        assertThat(domain.description()).isEqualTo("trireme");
        assertThat(domain.version()).isEqualTo(3L);
        assertThat(domain.performedByUserId()).isEqualTo(USER_ID);
        assertThat(domain.performedAt()).isEqualTo(PERFORMED_AT);
    }

    /**
     * Domain → Entity: places the supplied {@code performedBy} reference on
     * the entity (the domain's {@code performedByUserId} is intentionally
     * ignored — the adapter is responsible for resolving the reference).
     */
    @Test
    void toJpaEntity_placesPerformedByReference() {
        AppUserJpaEntity userRef = new AppUserJpaEntity(
                USER_ID, "kc-sub", "u", "u@x.test", null, null, PERFORMED_AT, PERFORMED_AT);
        BoatAudit domain = new BoatAudit(
                null, BOAT_ID, AuditAction.CREATED, "Argo", "trireme", 0L, USER_ID, PERFORMED_AT);

        BoatAuditJpaEntity entity = mapper.toJpaEntity(domain, userRef);

        assertThat(entity.getId()).isNull();
        assertThat(entity.getBoatId()).isEqualTo(BOAT_ID);
        assertThat(entity.getAction()).isEqualTo(AuditAction.CREATED);
        assertThat(entity.getName()).isEqualTo("Argo");
        assertThat(entity.getDescription()).isEqualTo("trireme");
        assertThat(entity.getVersion()).isEqualTo(0L);
        assertThat(entity.getPerformedBy()).isSameAs(userRef);
        assertThat(entity.getPerformedAt()).isEqualTo(PERFORMED_AT);
    }

    /** {@code toDomain(null)} → {@code null}. */
    @Test
    void toDomain_nullEntity_returnsNull() {
        assertThat(mapper.toDomain(null)).isNull();
    }

    /** {@code toJpaEntity(null, ref)} → {@code null}. */
    @Test
    void toJpaEntity_nullAudit_returnsNull() {
        AppUserJpaEntity userRef = new AppUserJpaEntity(
                USER_ID, "kc-sub", "u", "u@x.test", null, null, PERFORMED_AT, PERFORMED_AT);
        assertThat(mapper.toJpaEntity(null, userRef)).isNull();
    }
}
