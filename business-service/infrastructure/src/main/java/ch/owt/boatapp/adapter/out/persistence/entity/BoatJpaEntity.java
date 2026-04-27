package ch.owt.boatapp.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mirroring the {@code BOATS} table.
 *
 * <p>Lives in {@code adapter.out.persistence.entity} — the only place where
 * {@code @Entity} / {@code jakarta.persistence.*} annotations are allowed.
 * Domain code never references this class; persistence mappers convert
 * to/from {@link ch.owt.boatapp.domain.model.Boat}.
 *
 * <p>{@code id} is assigned by the domain (no {@code @GeneratedValue}). The
 * {@code @Version} field implements Hibernate's optimistic locking — any
 * stale-version save throws {@code OptimisticLockException} which the web
 * adapter (added in step 02a3) maps to HTTP 409.
 */
@Entity
@Table(name = "boats")
public class BoatJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** No-arg constructor required by JPA. */
    public BoatJpaEntity() {
    }

    /**
     * All-args constructor for convenience in tests and adapters.
     *
     * @param id          row identifier (assigned by the domain on create)
     * @param name        boat display name (≤ 64 chars)
     * @param description boat description (≤ 256 chars; nullable)
     * @param createdAt   creation timestamp in UTC
     * @param version     optimistic-locking version
     */
    public BoatJpaEntity(UUID id, String name, String description, OffsetDateTime createdAt, Long version) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.version = version;
    }

    /** @return the row identifier */
    public UUID getId() {
        return id;
    }

    /** @param id the new row identifier */
    public void setId(UUID id) {
        this.id = id;
    }

    /** @return the boat's display name */
    public String getName() {
        return name;
    }

    /** @param name the new display name */
    public void setName(String name) {
        this.name = name;
    }

    /** @return the boat's description, or {@code null} */
    public String getDescription() {
        return description;
    }

    /** @param description the new description */
    public void setDescription(String description) {
        this.description = description;
    }

    /** @return the creation timestamp in UTC */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt the new creation timestamp */
    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** @return the optimistic-locking version */
    public Long getVersion() {
        return version;
    }

    /** @param version the new optimistic-locking version */
    public void setVersion(Long version) {
        this.version = version;
    }
}
