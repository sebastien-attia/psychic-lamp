package ch.owt.boatapp.adapter.out.persistence.mapper;

import ch.owt.boatapp.adapter.out.persistence.entity.BoatJpaEntity;
import ch.owt.boatapp.domain.model.Boat;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between {@link Boat} (domain) and {@link BoatJpaEntity}
 * (persistence).
 *
 * <p>Field names match 1:1, so MapStruct generates trivial getter/setter
 * copy methods. {@code componentModel = "spring"} produces a
 * {@code @Component} implementation that the repository adapter injects.
 */
@Mapper(componentModel = "spring")
public interface BoatPersistenceMapper {

    /**
     * @param entity the JPA entity loaded from the database
     * @return the equivalent domain model, or {@code null} if {@code entity} is {@code null}
     */
    Boat toDomain(BoatJpaEntity entity);

    /**
     * @param boat the domain model to persist
     * @return the equivalent JPA entity, or {@code null} if {@code boat} is {@code null}
     */
    BoatJpaEntity toJpaEntity(Boat boat);
}
