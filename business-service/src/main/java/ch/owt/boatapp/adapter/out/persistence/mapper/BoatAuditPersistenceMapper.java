package ch.owt.boatapp.adapter.out.persistence.mapper;

import ch.owt.boatapp.adapter.out.persistence.entity.AppUserJpaEntity;
import ch.owt.boatapp.adapter.out.persistence.entity.BoatAuditJpaEntity;
import ch.owt.boatapp.domain.model.BoatAudit;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper between {@link BoatAudit} (domain) and
 * {@link BoatAuditJpaEntity} (persistence).
 *
 * <p>The relationship between {@code performedBy} (a JPA reference to
 * {@link AppUserJpaEntity}) and {@code performedByUserId} (a {@link java.util.UUID}
 * on the domain) is bridged in two directions:
 * <ul>
 *   <li>{@link #toDomain(BoatAuditJpaEntity)} flattens
 *       {@code performedBy.id} into {@code performedByUserId}.</li>
 *   <li>{@link #toJpaEntity(BoatAudit, AppUserJpaEntity)} accepts the
 *       lazy reference as a separate argument so the adapter can resolve it
 *       via {@code getReferenceById} without an extra SELECT.</li>
 * </ul>
 */
@Mapper(componentModel = "spring")
public interface BoatAuditPersistenceMapper {

    /**
     * @param entity the JPA entity loaded from the database
     * @return the equivalent domain model, or {@code null} if {@code entity} is {@code null}
     */
    @Mapping(target = "performedByUserId", source = "performedBy.id")
    BoatAudit toDomain(BoatAuditJpaEntity entity);

    /**
     * @param audit       the domain audit row to persist (its
     *                    {@code performedByUserId} is ignored — the
     *                    {@code performedBy} reference takes its place)
     * @param performedBy lazy JPA reference to the user who performed the action
     * @return the JPA entity ready to be persisted
     */
    @Mapping(target = "id", source = "audit.id")
    @Mapping(target = "boatId", source = "audit.boatId")
    @Mapping(target = "action", source = "audit.action")
    @Mapping(target = "name", source = "audit.name")
    @Mapping(target = "description", source = "audit.description")
    @Mapping(target = "version", source = "audit.version")
    @Mapping(target = "performedAt", source = "audit.performedAt")
    @Mapping(target = "performedBy", source = "performedBy")
    BoatAuditJpaEntity toJpaEntity(BoatAudit audit, AppUserJpaEntity performedBy);
}
