package ch.owt.boatapp.adapter.out.persistence.entity;

import ch.owt.boatapp.domain.model.AuditAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mirroring the {@code BOAT_AUDIT} table.
 *
 * <p>INSERT-ONLY at the application layer: the
 * {@link ch.owt.boatapp.application.port.out.BoatAuditRepositoryPort outbound port}
 * exposes only {@code save}, and the corresponding adapter rejects updates
 * by always inserting a new row. The schema itself permits any row but the
 * domain never issues an update or delete.
 *
 * <p>The {@code performedBy} association is lazy — the adapter uses
 * {@code getReferenceById} on {@link AppUserJpaEntity} to attach a proxy
 * without an extra SELECT.
 */
@Entity
@Table(name = "boat_audit")
public class BoatAuditJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "boat_id", nullable = false)
    private UUID boatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private AuditAction action;

    @Column(name = "name", length = 64)
    private String name;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "version")
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by_user_id", nullable = false, updatable = false)
    private AppUserJpaEntity performedBy;

    @Column(name = "performed_at", nullable = false)
    private OffsetDateTime performedAt;

    /** No-arg constructor required by JPA. */
    public BoatAuditJpaEntity() {
    }

    /**
     * All-args constructor for convenience in tests and adapters.
     *
     * @param id          row identifier (database-generated; {@code null} until persisted)
     * @param boatId      identifier of the boat the action targeted
     * @param action      kind of mutation
     * @param name        snapshot of the boat's name
     * @param description snapshot of the boat's description
     * @param version     snapshot of the boat's optimistic-locking version
     * @param performedBy lazy reference to the user who performed the action
     * @param performedAt UTC timestamp of the action
     */
    public BoatAuditJpaEntity(Long id, UUID boatId, AuditAction action, String name, String description,
                              Long version, AppUserJpaEntity performedBy, OffsetDateTime performedAt) {
        this.id = id;
        this.boatId = boatId;
        this.action = action;
        this.name = name;
        this.description = description;
        this.version = version;
        this.performedBy = performedBy;
        this.performedAt = performedAt;
    }

    /** @return the audit-row identifier */
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

    /** @return snapshot of the boat's name */
    public String getName() {
        return name;
    }

    /** @param name the new name snapshot */
    public void setName(String name) {
        this.name = name;
    }

    /** @return snapshot of the boat's description */
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

    /** @return the lazy reference to the user who performed the action */
    public AppUserJpaEntity getPerformedBy() {
        return performedBy;
    }

    /** @param performedBy the new performer reference */
    public void setPerformedBy(AppUserJpaEntity performedBy) {
        this.performedBy = performedBy;
    }

    /** @return UTC timestamp of the action */
    public OffsetDateTime getPerformedAt() {
        return performedAt;
    }

    /** @param performedAt the new action timestamp */
    public void setPerformedAt(OffsetDateTime performedAt) {
        this.performedAt = performedAt;
    }
}
