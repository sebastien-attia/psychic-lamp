package ch.owt.boatapp.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pure-Java immutable domain record for a boat.
 *
 * <p>Lives in {@code domain.model} — ArchUnit forbids any
 * {@code org.springframework.*} or {@code jakarta.*} import in this package.
 *
 * <p>The compact constructor stays empty by design: length / nullability
 * invariants are enforced by {@code SyntacticValidator} <em>before</em> the
 * use-case ever calls {@code new Boat(...)}, and reconstitution from the
 * persistence adapter must not pay re-validation cost. Updates rebuild a
 * fresh instance via the canonical constructor — there are no setters.
 *
 * @param id          unique identifier (assigned by the domain on create)
 * @param name        display name (≤ 64 chars; enforced by validators)
 * @param description optional description (≤ 256 chars; may be {@code null})
 * @param createdAt   creation timestamp in UTC
 * @param version     optimistic-locking version ({@code null} until first persist)
 */
public record Boat(UUID id, String name, String description, OffsetDateTime createdAt, Long version) {
}
