package ch.owt.boatapp.adapter.out.persistence.mapper;

import ch.owt.boatapp.adapter.out.persistence.entity.AppUserJpaEntity;
import ch.owt.boatapp.adapter.out.persistence.entity.BoatAuditJpaEntity;
import ch.owt.boatapp.domain.model.BoatAudit;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapper between {@link BoatAudit} (immutable domain record) and
 * {@link BoatAuditJpaEntity} (mutable JPA entity).
 *
 * <p>The relationship between {@code performedBy} (a JPA reference to
 * {@link AppUserJpaEntity}) and {@code performedByUserId} (a {@link java.util.UUID}
 * on the domain) is bridged in two directions:
 * <ul>
 *   <li>{@link #toDomain(BoatAuditJpaEntity)} flattens
 *       {@code performedBy.getId()} into {@code performedByUserId}. Reading
 *       the proxy's id does not trigger a SELECT — the FK column is already
 *       in memory.</li>
 *   <li>{@link #toJpaEntity(BoatAudit, AppUserJpaEntity)} accepts the lazy
 *       reference as a separate argument so the adapter can resolve it via
 *       {@code getReferenceById} without an extra SELECT.</li>
 * </ul>
 */
@Component
public class BoatAuditPersistenceMapper {

    /**
     * @param entity the JPA entity loaded from the database
     * @return the equivalent domain record, or {@code null} if {@code entity} is {@code null}
     */
    public BoatAudit toDomain(BoatAuditJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return new BoatAudit(entity.getId(), entity.getBoatId(), entity.getAction(),
                entity.getName(), entity.getDescription(), entity.getVersion(),
                entity.getPerformedBy().getId(), entity.getPerformedAt());
    }

    /**
     * @param audit       the domain audit row to persist (its
     *                    {@code performedByUserId} is ignored — the
     *                    {@code performedBy} reference takes its place)
     * @param performedBy lazy JPA reference to the user who performed the action
     * @return the JPA entity ready to be persisted, or {@code null} if {@code audit} is {@code null}
     */
    public BoatAuditJpaEntity toJpaEntity(BoatAudit audit, AppUserJpaEntity performedBy) {
        if (audit == null) {
            return null;
        }
        return new BoatAuditJpaEntity(audit.id(), audit.boatId(), audit.action(),
                audit.name(), audit.description(), audit.version(),
                performedBy, audit.performedAt());
    }
}
