package ch.owt.boatapp.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pure-Java domain model for a boat.
 *
 * <p>Carries the canonical attributes id (UUID), name (max 64), description
 * (max 256), createdAt (UTC {@code OffsetDateTime}), and the optimistic-locking
 * {@code version}.
 *
 * <p>Lives in {@code domain.model} — ArchUnit forbids any
 * {@code org.springframework.*} or {@code jakarta.*} import in this package.
 * Length and nullability invariants are enforced by {@code SyntacticValidator}
 * at the domain boundary; this class itself is intentionally permissive so it
 * can be reconstituted from persistence without re-validation.
 */
public class Boat {

    private UUID id;
    private String name;
    private String description;
    private OffsetDateTime createdAt;
    private Long version;

    /** No-arg constructor required for mutable reconstitution by mappers. */
    public Boat() {
    }

    /**
     * All-args constructor used by domain services and persistence mappers.
     *
     * @param id          unique identifier (assigned by the domain on create)
     * @param name        display name (≤ 64 chars; enforced by validators)
     * @param description optional description (≤ 256 chars; may be {@code null})
     * @param createdAt   creation timestamp in UTC
     * @param version     optimistic-locking version ({@code null} until first persist)
     */
    public Boat(UUID id, String name, String description, OffsetDateTime createdAt, Long version) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.version = version;
    }

    /** @return the boat's unique identifier */
    public UUID getId() {
        return id;
    }

    /** @param id the new identifier */
    public void setId(UUID id) {
        this.id = id;
    }

    /** @return the boat's display name */
    public String getName() {
        return name;
    }

    /** @param name the new display name (≤ 64 chars) */
    public void setName(String name) {
        this.name = name;
    }

    /** @return the boat's description, or {@code null} if none */
    public String getDescription() {
        return description;
    }

    /** @param description the new description (≤ 256 chars, nullable) */
    public void setDescription(String description) {
        this.description = description;
    }

    /** @return the creation timestamp in UTC */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt the new creation timestamp (UTC) */
    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** @return the optimistic-locking version, or {@code null} if not yet persisted */
    public Long getVersion() {
        return version;
    }

    /** @param version the new optimistic-locking version */
    public void setVersion(Long version) {
        this.version = version;
    }
}
