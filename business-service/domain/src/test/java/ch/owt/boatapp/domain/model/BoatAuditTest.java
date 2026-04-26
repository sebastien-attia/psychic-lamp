package ch.owt.boatapp.domain.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link BoatAudit} value-record contract. INSERT-only behavior is
 * enforced at the persistence layer (no update/delete methods on the
 * adapter); this test focuses on the value-record component contract:
 * pre-persist {@code id == null}, all three {@link AuditAction} values
 * supported, and {@code description} nullable per Javadoc.
 */
class BoatAuditTest {

    private static final UUID BOAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final OffsetDateTime PERFORMED_AT =
            OffsetDateTime.of(2026, 4, 26, 10, 0, 0, 0, ZoneOffset.UTC);

    /** A pre-persist audit row carries {@code id == null}; everything else is mandatory. */
    @Test
    void prePersistInstance_hasNullId() {
        BoatAudit audit = new BoatAudit(
                null, BOAT_ID, AuditAction.CREATED, "Argo", "trireme", 0L, USER_ID, PERFORMED_AT);

        assertThat(audit.id()).isNull();
        assertThat(audit.boatId()).isEqualTo(BOAT_ID);
        assertThat(audit.action()).isEqualTo(AuditAction.CREATED);
        assertThat(audit.performedByUserId()).isEqualTo(USER_ID);
        assertThat(audit.performedAt()).isEqualTo(PERFORMED_AT);
    }

    /** A {@link AuditAction#DELETED} row records the last-known state. */
    @Test
    void deletedRow_carriesLastKnownState() {
        BoatAudit audit = new BoatAudit(
                42L, BOAT_ID, AuditAction.DELETED, "RIP", "scuttled", 5L, USER_ID, PERFORMED_AT);

        assertThat(audit.action()).isEqualTo(AuditAction.DELETED);
        assertThat(audit.name()).isEqualTo("RIP");
        assertThat(audit.version()).isEqualTo(5L);
    }

    /** Every {@link AuditAction} can be persisted in an audit row. */
    @Test
    void allAuditActions_areAccepted() {
        for (AuditAction action : AuditAction.values()) {
            BoatAudit audit = new BoatAudit(
                    null, BOAT_ID, action, "n", null, 0L, USER_ID, PERFORMED_AT);
            assertThat(audit.action()).isEqualTo(action);
        }
    }

    /** Records implement value-based equality across every component. */
    @Test
    void recordEquality_isValueBased() {
        BoatAudit a = new BoatAudit(
                1L, BOAT_ID, AuditAction.UPDATED, "n", "d", 1L, USER_ID, PERFORMED_AT);
        BoatAudit b = new BoatAudit(
                1L, BOAT_ID, AuditAction.UPDATED, "n", "d", 1L, USER_ID, PERFORMED_AT);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
