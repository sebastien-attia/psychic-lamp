package ch.owt.boatapp.domain.model;

/**
 * Pure-Java domain model for a boat.
 *
 * <p>Carries the canonical attributes id (UUID), name (max 64), description
 * (max 256), createdAt (UTC {@code OffsetDateTime}), and the optimistic-locking
 * {@code version}.
 *
 * <p>Lives in {@code domain.model} — ArchUnit forbids any
 * {@code org.springframework.*} or {@code jakarta.*} import in this package.
 * Field declarations and value-object constructors are added in step 02a3.
 */
public class Boat {
}
