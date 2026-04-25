package ch.owt.boatapp.domain.port.out;

import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.PageResult;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port the domain uses to load and persist {@link Boat} aggregates.
 *
 * <p>Implemented in {@code adapter.out.persistence} by a Spring Data JPA
 * adapter; the domain stays unaware of JPA. All paging / sorting parameters
 * are passed as primitives + strings — no Spring {@code Pageable} leaks here.
 */
public interface BoatRepositoryPort {

    /**
     * Look up a boat by its identifier.
     *
     * @param id the boat identifier
     * @return the matching boat, or {@link Optional#empty()} if none exists
     */
    Optional<Boat> findById(UUID id);

    /**
     * List all boats with paging + sorting.
     *
     * @param page    zero-based page index
     * @param size    page size
     * @param sortBy  domain field name to sort by (e.g. {@code "name"})
     * @param sortDir sort direction — {@code "asc"} or {@code "desc"}
     * @return a page envelope holding the matching boats
     */
    PageResult<Boat> findAll(int page, int size, String sortBy, String sortDir);

    /**
     * Search boats whose name OR description matches a case-insensitive
     * substring, with paging + sorting.
     *
     * @param query   substring to match
     * @param page    zero-based page index
     * @param size    page size
     * @param sortBy  domain field name to sort by
     * @param sortDir sort direction — {@code "asc"} or {@code "desc"}
     * @return a page envelope holding the matching boats
     */
    PageResult<Boat> search(String query, int page, int size, String sortBy, String sortDir);

    /**
     * Persist (insert or update) a boat. The persisted state — including the
     * incremented optimistic-locking version — is returned.
     *
     * @param boat the boat to persist
     * @return the boat as it stands after persistence
     */
    Boat save(Boat boat);

    /**
     * Delete the boat with the given identifier. Behaviour is undefined if
     * the row does not exist — callers must verify existence first (e.g. by
     * loading the boat with {@link #findById(UUID)} and throwing
     * {@code BoatNotFoundException}). The use-case layer guarantees this
     * precondition before invoking the adapter.
     *
     * @param id the boat identifier
     */
    void deleteById(UUID id);
}
