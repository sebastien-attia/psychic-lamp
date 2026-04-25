package ch.owt.boatapp.domain.port.in;

/**
 * Query record carrying paging, sorting and search inputs for listing boats.
 *
 * <p>Pure-Java record. The web adapter is responsible for defaulting
 * {@code page=0}, a sensible {@code size}, and an allowed {@code sortBy}
 * field; the domain treats the values as already-validated paging hints.
 * A {@code null} or blank {@code search} means "no filter".
 *
 * @param page    zero-based page index
 * @param size    page size (number of elements per page)
 * @param sortBy  domain field name to sort by (e.g. {@code "name"}, {@code "createdAt"})
 * @param sortDir sort direction — {@code "asc"} or {@code "desc"}
 * @param search  optional case-insensitive substring filter applied to
 *                {@code name} and {@code description}; {@code null} or blank
 *                means no filter
 */
public record ListBoatsQuery(int page, int size, String sortBy, String sortDir, String search) {
}
