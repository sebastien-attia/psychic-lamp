package ch.owt.boatapp.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link PageResult} value-record contract. The persistence adapter
 * computes {@code totalPages} and {@code totalElements} from Spring's
 * {@code Page<T>} before constructing this; the domain just carries the
 * already-computed numbers. These tests therefore pin accessor pass-through,
 * generic-type erasure tolerance, and value equality — they do NOT compute
 * pagination math (that lives in the adapter).
 */
class PageResultTest {

    /** Empty page: no content, zero totalElements / totalPages. */
    @Test
    void emptyPage_hasZeroTotals() {
        PageResult<String> page = new PageResult<>(List.of(), 0L, 0, 10, 0);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.number()).isZero();
    }

    /** Single full page: content matches inputs, totals carry through. */
    @Test
    void singleFullPage_carriesThrough() {
        PageResult<String> page = new PageResult<>(
                List.of("a", "b", "c"), 3L, 1, 3, 0);

        assertThat(page.content()).containsExactly("a", "b", "c");
        assertThat(page.totalElements()).isEqualTo(3L);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    /** Last (partial) page: smaller content, same totalElements and totalPages. */
    @Test
    void lastPartialPage_keepsTotalsConsistent() {
        // 15 elements, page size 10, page index 1 → 5 elements on this slice
        PageResult<Integer> page = new PageResult<>(
                List.of(11, 12, 13, 14, 15), 15L, 2, 10, 1);

        assertThat(page.content()).hasSize(5);
        assertThat(page.totalElements()).isEqualTo(15L);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.number()).isEqualTo(1);
    }

    /** Records implement value-based equality across every component. */
    @Test
    void recordEquality_isValueBased() {
        PageResult<Integer> a = new PageResult<>(List.of(1, 2), 2L, 1, 10, 0);
        PageResult<Integer> b = new PageResult<>(List.of(1, 2), 2L, 1, 10, 0);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
