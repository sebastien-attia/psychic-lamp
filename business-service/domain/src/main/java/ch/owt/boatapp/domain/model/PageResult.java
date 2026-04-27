package ch.owt.boatapp.domain.model;

import java.util.List;

/**
 * Domain-side page envelope returned by paginated queries.
 *
 * <p>Pure-Java record — the domain never exposes Spring's {@code Page} so the
 * port API stays framework-agnostic. The persistence adapter constructs this
 * from a {@code Page<EntityType>} after mapping rows to domain objects.
 *
 * @param <T>           element type held in {@link #content}
 * @param content       the page's elements (never {@code null}; may be empty)
 * @param totalElements total number of matching rows across all pages
 * @param totalPages    total number of pages given the current {@link #size}
 * @param size          page size requested (number of elements per page)
 * @param number        zero-based page index of this slice
 */
public record PageResult<T>(List<T> content, long totalElements, int totalPages, int size, int number) {
}
