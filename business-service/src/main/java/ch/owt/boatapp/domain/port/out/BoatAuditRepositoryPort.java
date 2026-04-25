package ch.owt.boatapp.domain.port.out;

/**
 * Outbound port the domain uses to append {@link ch.owt.boatapp.domain.model.BoatAudit}
 * rows. INSERT-ONLY — the implementation must reject updates / deletes.
 *
 * <p>Implemented in {@code adapter.out.persistence}. Method signatures are
 * added in step 02a3.
 */
public interface BoatAuditRepositoryPort {
}
