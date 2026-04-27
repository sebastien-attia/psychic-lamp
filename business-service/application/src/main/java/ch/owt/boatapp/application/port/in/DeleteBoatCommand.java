package ch.owt.boatapp.application.port.in;

import ch.owt.boatapp.domain.model.BoatId;
import ch.owt.boatapp.domain.model.UserId;

/**
 * Command record carrying the inputs needed to delete a boat.
 *
 * <p>Pure-Java record. The audit row written before the delete snapshots the
 * boat's last-known state with {@link ch.owt.boatapp.domain.model.AuditAction#DELETED}
 * so its history is recoverable.
 *
 * @param id          identifier of the boat to delete
 * @param performedBy identifier of the {@link ch.owt.boatapp.domain.model.AppUser}
 *                    performing the action — recorded in the audit row
 */
public record DeleteBoatCommand(BoatId id, UserId performedBy) {
}
