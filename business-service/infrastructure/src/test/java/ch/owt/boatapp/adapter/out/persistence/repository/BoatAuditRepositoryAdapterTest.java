package ch.owt.boatapp.adapter.out.persistence.repository;

import ch.owt.boatapp.adapter.out.persistence.mapper.AppUserPersistenceMapper;
import ch.owt.boatapp.adapter.out.persistence.mapper.BoatAuditPersistenceMapper;
import ch.owt.boatapp.application.port.out.BoatAuditRepositoryPort;
import ch.owt.boatapp.domain.model.AppUser;
import ch.owt.boatapp.domain.model.AuditAction;
import ch.owt.boatapp.domain.model.BoatAudit;
import ch.owt.boatapp.testsupport.TestcontainersConfiguration;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @DataJpaTest} slice for {@link BoatAuditRepositoryAdapter}: pins the
 * INSERT-only contract, the FK to {@code APP_USER} (the lazy reference goes
 * through {@code getReferenceById}), and the absence of {@code update} /
 * {@code delete} entry points on the outbound port.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class,
        AppUserRepositoryAdapter.class, AppUserPersistenceMapper.class,
        BoatAuditRepositoryAdapter.class, BoatAuditPersistenceMapper.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BoatAuditRepositoryAdapterTest {

    @Autowired private BoatAuditRepositoryAdapter auditAdapter;
    @Autowired private AppUserRepositoryAdapter userAdapter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestEntityManager em;

    private static final OffsetDateTime AT =
            OffsetDateTime.of(2026, 4, 26, 10, 0, 0, 0, ZoneOffset.UTC);

    /**
     * Type-level enforcement: the outbound port {@link BoatAuditRepositoryPort}
     * exposes only {@code save(BoatAudit)} — no {@code update}, no {@code delete}.
     * This is the single most important contract on the audit table; pinning
     * it via reflection catches accidental method additions.
     */
    @Test
    void port_exposesOnlySave() {
        Method[] methods = BoatAuditRepositoryPort.class.getDeclaredMethods();
        assertThat(Arrays.stream(methods).map(Method::getName))
                .as("BoatAuditRepositoryPort must expose only save(...) — INSERT-only contract")
                .containsExactlyInAnyOrder("save");
    }

    /**
     * Happy path: save inserts a row pointing at an existing AppUser via the
     * lazy reference returned by {@code getReferenceById}, gets a generated id.
     */
    @Test
    void save_appendsRow_withGeneratedId() {
        AppUser user = userAdapter.save(newUser("kc-sub-audit-1"));
        UUID boatId = UUID.randomUUID();

        BoatAudit persisted = auditAdapter.save(new BoatAudit(
                null, boatId, AuditAction.CREATED, "Argo", "trireme", 0L, user.id(), AT));

        assertThat(persisted.id()).isNotNull().isPositive();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT boat_id, action, name, performed_by_user_id FROM boat_audit WHERE id = ?",
                persisted.id());
        assertThat(row.get("boat_id")).isEqualTo(boatId);
        assertThat(row.get("action")).isEqualTo("CREATED");
        assertThat(row.get("name")).isEqualTo("Argo");
        assertThat(row.get("performed_by_user_id")).isEqualTo(user.id());
    }

    /**
     * FK enforced: trying to save with a {@code performedByUserId} that has no
     * row in {@code APP_USER} fails at flush time. {@code getReferenceById}
     * returns a lazy proxy so the failure surfaces only when Hibernate flushes
     * the FK column — exactly the scenario {@code BoatAuditRepositoryPort}'s
     * Javadoc warns about (the web layer must {@code syncUser} first).
     */
    @Test
    void save_unknownPerformedByUserId_violatesForeignKey() {
        UUID nonExistentUserId = UUID.randomUUID();

        // Explicit em.flush() forces the FK check to fire inside the lambda;
        // @DataJpaTest's transaction wrapper otherwise defers flushes to commit
        // (which never happens — the test rolls back).
        assertThatThrownBy(() -> {
            auditAdapter.save(new BoatAudit(
                    null, UUID.randomUUID(), AuditAction.CREATED, "n", null, 0L, nonExistentUserId, AT));
            em.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }

    /** Multiple appends for the same boat → multiple rows (INSERT-only, no upsert). */
    @Test
    void save_multipleActions_keepsEveryRow() {
        AppUser user = userAdapter.save(newUser("kc-sub-audit-2"));
        UUID boatId = UUID.randomUUID();

        auditAdapter.save(new BoatAudit(null, boatId, AuditAction.CREATED, "n", null, 0L, user.id(), AT));
        auditAdapter.save(new BoatAudit(null, boatId, AuditAction.UPDATED, "n", null, 1L, user.id(), AT));
        auditAdapter.save(new BoatAudit(null, boatId, AuditAction.DELETED, "n", null, 1L, user.id(), AT));

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boat_audit WHERE boat_id = ?", Long.class, boatId);
        assertThat(count).isEqualTo(3L);
    }

    private AppUser newUser(String keycloakId) {
        return new AppUser(
                UUID.randomUUID(), keycloakId, keycloakId, keycloakId + "@x.test",
                "F", "L", AT, AT);
    }
}
