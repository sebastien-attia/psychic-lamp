package ch.owt.boatapp.adapter.out.persistence.repository;

import ch.owt.boatapp.adapter.out.persistence.mapper.AppUserPersistenceMapper;
import ch.owt.boatapp.domain.model.AppUser;
import ch.owt.boatapp.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @DataJpaTest} slice for {@link AppUserRepositoryAdapter}: pins the
 * {@code findByKeycloakId} hit/miss contract, the insert/update branches of
 * {@code save}, and the database-level UNIQUE constraint on {@code keycloak_id}
 * (the upsert key the {@code UserDomainService} relies on).
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, AppUserRepositoryAdapter.class, AppUserPersistenceMapper.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AppUserRepositoryAdapterTest {

    @Autowired private AppUserRepositoryAdapter adapter;
    @Autowired private TestEntityManager em;

    private static final OffsetDateTime LOGIN_AT =
            OffsetDateTime.of(2026, 4, 26, 10, 0, 0, 0, ZoneOffset.UTC);

    /** {@code save} → {@code findByKeycloakId}: round-trip works. */
    @Test
    void save_then_findByKeycloakId_returnsRow() {
        AppUser saved = adapter.save(newUser("kc-sub-1", "alice"));

        Optional<AppUser> loaded = adapter.findByKeycloakId("kc-sub-1");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo(saved.id());
        assertThat(loaded.get().username()).isEqualTo("alice");
    }

    /** Unknown {@code keycloakId} → empty. */
    @Test
    void findByKeycloakId_missing_returnsEmpty() {
        assertThat(adapter.findByKeycloakId("kc-missing")).isEmpty();
    }

    /**
     * Saving a second row with the same {@code keycloakId} but a different
     * {@code id} violates the UNIQUE constraint at the database level.
     * Confirms the constraint exists — the application service is the one
     * that performs the upsert and so must never trigger this in production.
     */
    @Test
    void save_duplicateKeycloakId_violatesUniqueConstraint() {
        adapter.save(newUser("kc-sub-dup", "first"));
        em.flush();

        // The second insert hits the database UNIQUE constraint on flush — the
        // explicit flush() is required because @DataJpaTest's transaction
        // wrapper otherwise defers the constraint check to commit (which never
        // happens — the test rolls back). em.flush() bypasses Spring's
        // exception translation, so the raw JPA / Hibernate exception
        // propagates rather than the translated DataIntegrityViolationException.
        assertThatThrownBy(() -> {
            adapter.save(newUser("kc-sub-dup", "second"));
            em.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }

    /**
     * Saving with the SAME id (and same keycloakId) is the update branch —
     * succeeds and overwrites the mutable claim columns.
     */
    @Test
    void save_sameIdAndKeycloakId_updatesRow() {
        AppUser created = adapter.save(newUser("kc-sub-2", "alice"));
        AppUser refreshed = new AppUser(
                created.id(), created.keycloakId(), "alice-renamed", "alice@new.test",
                "Ali", "Anderson", created.firstLogin(), LOGIN_AT.plusHours(1));

        adapter.save(refreshed);

        AppUser loaded = adapter.findByKeycloakId("kc-sub-2").orElseThrow();
        assertThat(loaded.username()).isEqualTo("alice-renamed");
        assertThat(loaded.email()).isEqualTo("alice@new.test");
        assertThat(loaded.lastLogin()).isAfter(loaded.firstLogin());
    }

    private AppUser newUser(String keycloakId, String username) {
        return new AppUser(
                UUID.randomUUID(), keycloakId, username, username + "@x.test",
                "First", "Last", LOGIN_AT, LOGIN_AT);
    }
}
