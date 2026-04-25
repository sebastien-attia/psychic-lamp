package ch.owt.boatapp.adapter.out.persistence.mapper;

import ch.owt.boatapp.adapter.out.persistence.entity.AppUserJpaEntity;
import ch.owt.boatapp.domain.model.AppUser;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapper between {@link AppUser} (immutable domain record) and
 * {@link AppUserJpaEntity} (mutable JPA entity).
 *
 * <p>Plain {@code @Component}: no annotation processor, no generated code.
 */
@Component
public class AppUserPersistenceMapper {

    /**
     * @param entity the JPA entity loaded from the database
     * @return the equivalent domain record, or {@code null} if {@code entity} is {@code null}
     */
    public AppUser toDomain(AppUserJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return new AppUser(entity.getId(), entity.getKeycloakId(), entity.getUsername(),
                entity.getEmail(), entity.getFirstName(), entity.getLastName(),
                entity.getFirstLogin(), entity.getLastLogin());
    }

    /**
     * @param user the domain record to persist
     * @return the equivalent JPA entity, or {@code null} if {@code user} is {@code null}
     */
    public AppUserJpaEntity toJpaEntity(AppUser user) {
        if (user == null) {
            return null;
        }
        return new AppUserJpaEntity(user.id(), user.keycloakId(), user.username(),
                user.email(), user.firstName(), user.lastName(),
                user.firstLogin(), user.lastLogin());
    }
}
