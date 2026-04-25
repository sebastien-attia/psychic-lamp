package ch.owt.boatapp.adapter.out.persistence.repository;

import ch.owt.boatapp.adapter.out.persistence.mapper.AppUserPersistenceMapper;
import ch.owt.boatapp.domain.model.AppUser;
import ch.owt.boatapp.domain.port.out.AppUserRepositoryPort;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA implementation of {@link AppUserRepositoryPort}.
 *
 * <p>Translates domain calls into Spring Data operations and maps results
 * back to {@link AppUser} via {@link AppUserPersistenceMapper}.
 */
@Repository
public class AppUserRepositoryAdapter implements AppUserRepositoryPort {

    private final AppUserJpaRepository jpaRepository;
    private final AppUserPersistenceMapper mapper;

    /**
     * @param jpaRepository the underlying Spring Data JPA repository
     * @param mapper        MapStruct mapper for entity ↔ domain conversion
     */
    public AppUserRepositoryAdapter(AppUserJpaRepository jpaRepository, AppUserPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<AppUser> findByKeycloakId(String keycloakId) {
        return jpaRepository.findByKeycloakId(keycloakId).map(mapper::toDomain);
    }

    @Override
    public AppUser save(AppUser user) {
        return mapper.toDomain(jpaRepository.save(mapper.toJpaEntity(user)));
    }
}
