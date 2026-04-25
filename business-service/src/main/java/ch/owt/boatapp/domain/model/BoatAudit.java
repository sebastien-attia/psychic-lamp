package ch.owt.boatapp.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pure-Java domain model for a boat-audit record.
 *
 * <p>INSERT-ONLY: every domain mutation on a {@link Boat} appends a row
 * referencing the acting {@link AppUser} (FK on the persistence side).
 * Lives in {@code domain.model} — no Spring, no Jakarta. The
 * {@code name}, {@code description} and {@code version} fields snapshot the
 * boat's state at the moment of the action; for {@link AuditAction#DELETED}
 * they record the last-known state before deletion.
 */
public class BoatAudit {

    private Long id;
    private UUID boatId;
    private AuditAction action;
    private String name;
    private String description;
    private Long version;
    private UUID performedByUserId;
    private OffsetDateTime performedAt;

    /** No-arg constructor required for mutable reconstitution by mappers. */
    public BoatAudit() {
    }

    /**
     * All-args constructor used by domain services and persistence mappers.
     *
     * @param id                audit-row identifier (database-generated; {@code null} until persisted)
     * @param boatId            identifier of the boat the action targeted
     * @param action            kind of mutation: {@link AuditAction#CREATED CREATED},
     *                          {@link AuditAction#UPDATED UPDATED}, or
     *                          {@link AuditAction#DELETED DELETED}
     * @param name              snapshot of the boat's name at the time of the action
     * @param description       snapshot of the boat's description at the time of the action
     * @param version           snapshot of the boat's optimistic-locking version
     * @param performedByUserId identifier of the {@link AppUser} who performed the action
     * @param performedAt       UTC timestamp when the action was recorded
     */
    public BoatAudit(Long id, UUID boatId, AuditAction action, String name, String description,
                     Long version, UUID performedByUserId, OffsetDateTime performedAt) {
        this.id = id;
        this.boatId = boatId;
        this.action = action;
        this.name = name;
        this.description = description;
        this.version = version;
        this.performedByUserId = performedByUserId;
        this.performedAt = performedAt;
    }

    /** @return the audit-row identifier (database-generated) */
    public Long getId() {
        return id;
    }

    /** @param id the new audit-row identifier */
    public void setId(Long id) {
        this.id = id;
    }

    /** @return the identifier of the boat the action targeted */
    public UUID getBoatId() {
        return boatId;
    }

    /** @param boatId the new boat identifier */
    public void setBoatId(UUID boatId) {
        this.boatId = boatId;
    }

    /** @return the audit action */
    public AuditAction getAction() {
        return action;
    }

    /** @param action the new audit action */
    public void setAction(AuditAction action) {
        this.action = action;
    }

    /** @return snapshot of the boat's name at the time of the action */
    public String getName() {
        return name;
    }

    /** @param name the new name snapshot */
    public void setName(String name) {
        this.name = name;
    }

    /** @return snapshot of the boat's description at the time of the action */
    public String getDescription() {
        return description;
    }

    /** @param description the new description snapshot */
    public void setDescription(String description) {
        this.description = description;
    }

    /** @return snapshot of the boat's optimistic-locking version */
    public Long getVersion() {
        return version;
    }

    /** @param version the new version snapshot */
    public void setVersion(Long version) {
        this.version = version;
    }

    /** @return identifier of the {@link AppUser} who performed the action */
    public UUID getPerformedByUserId() {
        return performedByUserId;
    }

    /** @param performedByUserId the new performer identifier */
    public void setPerformedByUserId(UUID performedByUserId) {
        this.performedByUserId = performedByUserId;
    }

    /** @return UTC timestamp when the action was recorded */
    public OffsetDateTime getPerformedAt() {
        return performedAt;
    }

    /** @param performedAt the new action timestamp */
    public void setPerformedAt(OffsetDateTime performedAt) {
        this.performedAt = performedAt;
    }
}
