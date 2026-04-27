package ch.owt.boatapp.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins two startup-time invariants that none of the other integration tests
 * directly assert:
 *
 * <ol>
 *   <li>Every Liquibase changeset that ships with the
 *       {@code db.changelog-master.yaml} included files runs at startup —
 *       {@code databasechangelog} carries one row per applied changeset.</li>
 *   <li>The dev-mode dummy {@code AppUser} (keycloakId {@code dev-user}) is
 *       NOT inserted under the production profile (this test class runs with
 *       no active profile, so {@code DevSecurityConfig}'s
 *       {@code @Profile("dev")} bootstrap should be inactive).</li>
 * </ol>
 *
 * <p>Both properties matter because either silently breaking them would not
 * surface as a test failure today: the integration tests truncate
 * {@code app_user} between methods, masking either an extra dev-user row or
 * a missing changelog row. This class is the regression guard for that.
 */
class LiquibaseAndStartupTest extends IntegrationTestBase {

    /**
     * Liquibase ran every expected changeset on context startup. The changelog
     * tracking table {@code databasechangelog} carries one row per applied
     * changeset; we expect at least the three known changelogs declared in
     * {@code db.changelog-master.yaml}.
     */
    @Test
    void liquibase_appliedAllExpectedChangesets() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT filename FROM databasechangelog ORDER BY orderexecuted");

        assertThat(rows)
                .as("Liquibase must have applied at least the 3 master-changelog includes")
                .hasSizeGreaterThanOrEqualTo(3)
                .extracting(row -> row.get("filename").toString())
                .anyMatch(name -> name.contains("001-create-app-user-table"))
                .anyMatch(name -> name.contains("002-create-boats-table"))
                .anyMatch(name -> name.contains("003-create-boat-audit-table"));
    }

    /**
     * Without the {@code dev} profile, {@code DevSecurityConfig} must NOT
     * fire — the dummy {@code dev-user} row stays absent. {@code DevModeTest}
     * activates the profile and asserts the opposite; this test pins the
     * "inactive" branch of the same invariant.
     */
    @Test
    void devProfileInactive_noDummyUserSeeded() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE keycloak_id = 'dev-user'", Long.class);

        assertThat(count)
                .as("Without @ActiveProfiles(\"dev\"), the dev dummy AppUser must NOT be seeded")
                .isZero();
    }
}
