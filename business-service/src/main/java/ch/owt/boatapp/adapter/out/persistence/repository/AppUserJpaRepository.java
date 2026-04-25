package ch.owt.boatapp.adapter.out.persistence.repository;

import ch.owt.boatapp.adapter.out.persistence.entity.AppUserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link AppUserJpaEntity}.
 *
 * <p>Consumed by {@link AppUserRepositoryAdapter}, which converts results
 * to domain models before returning them across the port boundary. Also
 * exposes {@code getReferenceById(UUID)} (inherited) used by the boat-audit
 * adapter to attach a lazy {@code @ManyToOne} reference without an extra
 * SELECT.
 */
public interface AppUserJpaRepository extends JpaRepository<AppUserJpaEntity, UUID> {

    /**
     * Look up the user keyed by JWT {@code sub} claim.
     *
     * @param keycloakId JWT {@code sub} claim
     * @return the matching entity, or {@link Optional#empty()} if none exists
     */
    Optional<AppUserJpaEntity> findByKeycloakId(String keycloakId);
}
