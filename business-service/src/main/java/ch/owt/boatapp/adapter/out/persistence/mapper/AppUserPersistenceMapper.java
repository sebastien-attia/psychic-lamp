package ch.owt.boatapp.adapter.out.persistence.mapper;

import ch.owt.boatapp.adapter.out.persistence.entity.AppUserJpaEntity;
import ch.owt.boatapp.domain.model.AppUser;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between {@link AppUser} (domain) and
 * {@link AppUserJpaEntity} (persistence).
 *
 * <p>Field names match 1:1. {@code componentModel = "spring"} produces a
 * {@code @Component} implementation that the repository adapter injects.
 */
@Mapper(componentModel = "spring")
public interface AppUserPersistenceMapper {

    /**
     * @param entity the JPA entity loaded from the database
     * @return the equivalent domain model, or {@code null} if {@code entity} is {@code null}
     */
    AppUser toDomain(AppUserJpaEntity entity);

    /**
     * @param user the domain model to persist
     * @return the equivalent JPA entity, or {@code null} if {@code user} is {@code null}
     */
    AppUserJpaEntity toJpaEntity(AppUser user);
}
