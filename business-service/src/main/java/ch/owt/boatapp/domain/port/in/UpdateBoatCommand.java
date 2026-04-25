package ch.owt.boatapp.domain.port.in;

import ch.owt.boatapp.domain.model.BoatId;
import ch.owt.boatapp.domain.model.UserId;

/**
 * Command record carrying the inputs needed to update an existing boat.
 *
 * <p>Pure-Java record. The {@code expectedVersion} drives optimistic locking:
 * it carries the {@code If-Match} ETag value the client supplied and is
 * compared against the persisted row's version on save. A mismatch surfaces
 * as {@code OptimisticLockException} → HTTP 409 (per the RFC 9457 registry).
 *
 * @param id              identifier of the boat to update
 * @param name            new display name (≤ 64 chars)
 * @param description     new description (≤ 256 chars; may be {@code null})
 * @param expectedVersion the version the caller believes the row holds
 *                        (used for optimistic-locking)
 * @param performedBy     identifier of the {@link ch.owt.boatapp.domain.model.AppUser}
 *                        performing the action — recorded in the audit row
 */
public record UpdateBoatCommand(BoatId id, String name, String description,
                                Long expectedVersion, UserId performedBy) {
}
