package ch.owt.boatapp.adapter.out.persistence.repository;

import ch.owt.boatapp.adapter.out.persistence.entity.BoatAuditJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link BoatAuditJpaEntity}.
 *
 * <p>The application layer never updates or deletes audit rows; this
 * interface deliberately exposes no derived query methods, so insertions
 * via {@code save(...)} are the only path used.
 */
public interface BoatAuditJpaRepository extends JpaRepository<BoatAuditJpaEntity, Long> {
}
