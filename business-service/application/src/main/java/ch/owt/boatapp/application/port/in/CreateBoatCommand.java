package ch.owt.boatapp.application.port.in;

import ch.owt.boatapp.domain.model.UserId;

/**
 * Command record carrying the inputs needed to create a new boat.
 *
 * <p>Pure-Java record co-located with the inbound port it serves. Field
 * invariants (non-blank name, length bounds) are enforced by the domain
 * validators inside {@code BoatDomainService.createBoat(...)} — not by
 * compact-constructor checks on this record, so callers can hold partially
 * filled commands while assembling them.
 *
 * @param name        proposed display name of the boat (≤ 64 chars)
 * @param description optional description (≤ 256 chars; may be {@code null})
 * @param performedBy identifier of the {@link ch.owt.boatapp.domain.model.AppUser}
 *                    performing the action — recorded in the audit row
 */
public record CreateBoatCommand(String name, String description, UserId performedBy) {
}
