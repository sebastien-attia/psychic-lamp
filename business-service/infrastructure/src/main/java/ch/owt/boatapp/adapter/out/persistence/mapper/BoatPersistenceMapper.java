package ch.owt.boatapp.adapter.out.persistence.mapper;

import ch.owt.boatapp.adapter.out.persistence.entity.BoatJpaEntity;
import ch.owt.boatapp.domain.model.Boat;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapper between {@link Boat} (immutable domain record) and
 * {@link BoatJpaEntity} (mutable JPA entity).
 *
 * <p>Plain {@code @Component}: no annotation processor, no generated code —
 * the mapping is expressed directly so it shows up under jump-to-definition,
 * is trivially debuggable, and adds no build-time dependency.
 */
@Component
public class BoatPersistenceMapper {

    /**
     * @param entity the JPA entity loaded from the database
     * @return the equivalent domain record, or {@code null} if {@code entity} is {@code null}
     */
    public Boat toDomain(BoatJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return new Boat(entity.getId(), entity.getName(), entity.getDescription(),
                entity.getCreatedAt(), entity.getVersion());
    }

    /**
     * @param boat the domain record to persist
     * @return the equivalent JPA entity, or {@code null} if {@code boat} is {@code null}
     */
    public BoatJpaEntity toJpaEntity(Boat boat) {
        if (boat == null) {
            return null;
        }
        return new BoatJpaEntity(boat.id(), boat.name(), boat.description(),
                boat.createdAt(), boat.version());
    }
}
