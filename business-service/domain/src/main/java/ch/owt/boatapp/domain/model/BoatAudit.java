package ch.owt.boatapp.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pure-Java immutable domain record for a boat-audit row.
 *
 * <p>INSERT-ONLY: every domain mutation on a {@link Boat} appends a row
 * referencing the acting {@link AppUser} (FK on the persistence side).
 * Lives in {@code domain.model} — no Spring, no Jakarta. The {@code name},
 * {@code description} and {@code version} components snapshot the boat's
 * state at the moment of the action; for {@link AuditAction#DELETED} they
 * record the last-known state before deletion.
 *
 * @param id                audit-row identifier (database-generated; {@code null} until persisted)
 * @param boatId            identifier of the boat the action targeted
 * @param action            kind of mutation
 * @param name              snapshot of the boat's name at the time of the action
 * @param description       snapshot of the boat's description
 * @param version           snapshot of the boat's optimistic-locking version
 * @param performedByUserId identifier of the {@link AppUser} who performed the action
 * @param performedAt       UTC timestamp when the action was recorded
 */
public record BoatAudit(Long id, UUID boatId, AuditAction action, String name, String description,
                        Long version, UUID performedByUserId, OffsetDateTime performedAt) {
}
