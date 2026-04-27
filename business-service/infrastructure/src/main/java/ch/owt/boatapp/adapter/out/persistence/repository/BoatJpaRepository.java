package ch.owt.boatapp.adapter.out.persistence.repository;

import ch.owt.boatapp.adapter.out.persistence.entity.BoatJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Spring Data JPA repository for {@link BoatJpaEntity}.
 *
 * <p>Implementation supplied at runtime by Spring Data; consumed by
 * {@link BoatRepositoryAdapter}, which converts results to domain models
 * before returning them across the port boundary.
 */
public interface BoatJpaRepository extends JpaRepository<BoatJpaEntity, UUID> {

    /**
     * Case-insensitive substring search across {@code name} and
     * {@code description}, with explicit {@code ESCAPE} so user-supplied
     * {@code %} / {@code _} are matched as literals (the adapter pre-escapes
     * them with {@code \}).
     *
     * @param term     the substring to match (must be pre-escaped for LIKE wildcards)
     * @param pageable paging + sorting hints
     * @return a page of matching boats
     */
    @Query("""
           SELECT b FROM BoatJpaEntity b
           WHERE LOWER(b.name) LIKE LOWER(CONCAT('%', :term, '%')) ESCAPE '\\'
              OR LOWER(b.description) LIKE LOWER(CONCAT('%', :term, '%')) ESCAPE '\\'
           """)
    Page<BoatJpaEntity> searchByTerm(@Param("term") String term, Pageable pageable);
}
