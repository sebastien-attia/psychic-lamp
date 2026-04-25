package ch.owt.boatapp.adapter.out.persistence.repository;

import ch.owt.boatapp.adapter.out.persistence.entity.BoatJpaEntity;
import ch.owt.boatapp.adapter.out.persistence.mapper.BoatPersistenceMapper;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.domain.port.out.BoatRepositoryPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA implementation of {@link BoatRepositoryPort}.
 *
 * <p>Lives in {@code adapter.out.persistence} — translates domain calls into
 * Spring Data operations and maps results back to {@link Boat} via
 * {@link BoatPersistenceMapper}. Domain code never sees Spring's
 * {@code Page} / {@code Pageable} types.
 */
@Repository
public class BoatRepositoryAdapter implements BoatRepositoryPort {

    private final BoatJpaRepository jpaRepository;
    private final BoatPersistenceMapper mapper;

    /**
     * @param jpaRepository the underlying Spring Data JPA repository
     * @param mapper        MapStruct mapper for entity ↔ domain conversion
     */
    public BoatRepositoryAdapter(BoatJpaRepository jpaRepository, BoatPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<Boat> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public PageResult<Boat> findAll(int page, int size, String sortBy, String sortDir) {
        Pageable pageable = PageRequest.of(page, size, sortOf(sortBy, sortDir));
        return toPageResult(jpaRepository.findAll(pageable));
    }

    @Override
    public PageResult<Boat> search(String query, int page, int size, String sortBy, String sortDir) {
        Pageable pageable = PageRequest.of(page, size, sortOf(sortBy, sortDir));
        Page<BoatJpaEntity> hits = jpaRepository.searchByTerm(escapeLikeWildcards(query), pageable);
        return toPageResult(hits);
    }

    @Override
    public Boat save(Boat boat) {
        // saveAndFlush (vs save) so Hibernate increments @Version *before*
        // we map back to the immutable domain record. Otherwise the merged
        // entity returned by save() carries the pre-flush version, the
        // controller serializes that into the ETag header, and the next
        // PUT (using that ETag as If-Match) would deterministically conflict.
        BoatJpaEntity persisted = jpaRepository.saveAndFlush(mapper.toJpaEntity(boat));
        return mapper.toDomain(persisted);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

    private Sort sortOf(String sortBy, String sortDir) {
        // Locale.ROOT-lowercase first to keep direction parsing deterministic
        // across JVM locales (Turkish would otherwise mangle the comparison).
        String dir = sortDir == null ? "" : sortDir.toLowerCase(Locale.ROOT);
        Sort.Direction direction = "desc".equals(dir)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        // Always append a stable secondary sort on `id` so paging is deterministic
        // even when the primary sort key has duplicate values (or is absent).
        if (sortBy == null || sortBy.isBlank()) {
            return Sort.by(Sort.Direction.ASC, "id");
        }
        return Sort.by(direction, sortBy).and(Sort.by(Sort.Direction.ASC, "id"));
    }

    private String escapeLikeWildcards(String term) {
        if (term == null) {
            return "";
        }
        // Backslash first, otherwise the escapes we add for % and _ get re-escaped.
        return term.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private PageResult<Boat> toPageResult(Page<BoatJpaEntity> page) {
        List<Boat> content = page.getContent().stream().map(mapper::toDomain).toList();
        return new PageResult<>(content, page.getTotalElements(), page.getTotalPages(),
                page.getSize(), page.getNumber());
    }
}
