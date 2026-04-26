package ch.owt.boatapp.adapter.out.persistence.repository;

import ch.owt.boatapp.adapter.out.persistence.mapper.BoatPersistenceMapper;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @DataJpaTest} slice for {@link BoatRepositoryAdapter}: pins the
 * persistence-layer behavior the application service relies on.
 *
 * <p>Runs against the same PostgreSQL Testcontainer the bootstrap-module
 * integration tests use (via {@link TestcontainersConfiguration}) — the
 * default H2 replacement is disabled because Postgres semantics
 * (case-insensitive {@code LIKE}, ESCAPE clause behavior, UUID column type,
 * Hibernate's optimistic-lock translation to
 * {@link OptimisticLockingFailureException}) differ from H2 in ways that
 * matter to this adapter.
 *
 * <p>Liquibase runs at startup so the {@code boats} schema is in place. Each
 * test runs inside Spring's default transaction-rollback wrapper, so the
 * shared container stays clean between tests within this class — and across
 * test classes that hit different tables (audit, app_user) when we run them
 * sequentially.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, BoatRepositoryAdapter.class, BoatPersistenceMapper.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BoatRepositoryAdapterTest {

    @Autowired private BoatRepositoryAdapter adapter;

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.of(2026, 4, 26, 10, 0, 0, 0, ZoneOffset.UTC);

    /** {@code save} → {@code findById}: a simple round-trip works. */
    @Test
    void save_then_findById_returnsBoat() {
        UUID id = UUID.randomUUID();
        Boat saved = adapter.save(new Boat(id, "Argo", "trireme", CREATED_AT, null));

        Optional<Boat> loaded = adapter.findById(id);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().name()).isEqualTo("Argo");
        assertThat(saved.version()).isZero();
    }

    /**
     * {@code save} uses {@code saveAndFlush}, so the returned domain record
     * carries the bumped {@code version} immediately — without this, the
     * controller would emit a stale ETag and the next If-Match would always
     * conflict.
     */
    @Test
    void save_returnsBumpedVersion_immediately() {
        UUID id = UUID.randomUUID();
        Boat created = adapter.save(new Boat(id, "Argo", "trireme", CREATED_AT, null));
        assertThat(created.version()).isZero();

        Boat updated = adapter.save(new Boat(id, "Argo II", "trireme", CREATED_AT, created.version()));
        assertThat(updated.version()).isEqualTo(1L);
    }

    /** {@code findById} miss → {@code Optional.empty()}. */
    @Test
    void findById_missing_returnsEmpty() {
        assertThat(adapter.findById(UUID.randomUUID())).isEmpty();
    }

    /** {@code findAll} respects paging and {@code asc} ordering by {@code name}. */
    @Test
    void findAll_paginates_andOrdersByName() {
        for (String n : new String[]{"alpha", "bravo", "charlie", "delta", "echo"}) {
            adapter.save(new Boat(UUID.randomUUID(), n, null, CREATED_AT, null));
        }

        PageResult<Boat> page1 = adapter.findAll(0, 2, "name", "asc");

        assertThat(page1.totalElements()).isEqualTo(5);
        assertThat(page1.totalPages()).isEqualTo(3);
        assertThat(page1.content()).extracting(Boat::name).containsExactly("alpha", "bravo");
    }

    /** {@code search} is case-insensitive and matches partial substrings on both fields. */
    @Test
    void search_isCaseInsensitive_matchesNameAndDescription() {
        adapter.save(new Boat(UUID.randomUUID(), "Black Pearl", "haunted", CREATED_AT, null));
        adapter.save(new Boat(UUID.randomUUID(), "White Whale", "MOBY-pursuer", CREATED_AT, null));
        adapter.save(new Boat(UUID.randomUUID(), "Argo", "trireme", CREATED_AT, null));

        // case-insensitive match on name
        assertThat(adapter.search("PEARL", 0, 10, "name", "asc").totalElements()).isEqualTo(1);
        // case-insensitive match on description
        assertThat(adapter.search("moby", 0, 10, "name", "asc").totalElements()).isEqualTo(1);
        // miss
        assertThat(adapter.search("zzz", 0, 10, "name", "asc").totalElements()).isZero();
    }

    /**
     * User-supplied LIKE wildcards are escaped — a bare "%" should NOT match
     * every row. The adapter pre-escapes {@code %}, {@code _} and backslash
     * before handing the term to the {@code searchByTerm} JPQL query.
     */
    @Test
    void search_escapesUserSuppliedLikeWildcards() {
        adapter.save(new Boat(UUID.randomUUID(), "alpha", null, CREATED_AT, null));
        adapter.save(new Boat(UUID.randomUUID(), "bravo", null, CREATED_AT, null));

        // "%" must be treated as a literal — no rows contain that character.
        assertThat(adapter.search("%", 0, 10, "name", "asc").totalElements()).isZero();
        // "_" likewise.
        assertThat(adapter.search("_", 0, 10, "name", "asc").totalElements()).isZero();
    }

    /** {@code deleteById} removes the row; subsequent {@code findById} → empty. */
    @Test
    void deleteById_removesRow() {
        UUID id = UUID.randomUUID();
        adapter.save(new Boat(id, "Argo", null, CREATED_AT, null));

        adapter.deleteById(id);

        assertThat(adapter.findById(id)).isEmpty();
    }

    /**
     * Optimistic-lock guard: writing with a stale {@code version} throws
     * {@link OptimisticLockingFailureException} (Spring's translation of
     * Hibernate's {@code StaleObjectStateException}).
     */
    @Test
    void save_withStaleVersion_throwsOptimisticLockingFailure() {
        UUID id = UUID.randomUUID();
        Boat created = adapter.save(new Boat(id, "Argo", null, CREATED_AT, null));
        assertThat(created.version()).isZero();
        // Bump once → version becomes 1.
        adapter.save(new Boat(id, "Argo II", null, CREATED_AT, created.version()));

        assertThatThrownBy(() ->
                adapter.save(new Boat(id, "Stale Argo", null, CREATED_AT, 0L)))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
