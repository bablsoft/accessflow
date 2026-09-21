package com.bablsoft.accessflow.deploygov.internal.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upgrade test for V177 (#877): drives Flyway to V176 on a private container, seeds the pre-#877
 * shape — every environment at {@code sort_order = 0} plus a hand-typed gap — and then applies
 * V177, so the backfill <em>and</em> the {@code ADD CONSTRAINT} that depends on it run exactly
 * as they will on an upgraded tenant. Fresh containers migrate an empty schema, which is why a
 * replay against the shared Testcontainers database could never prove the constraint applies.
 */
class DeploymentEnvironmentSortOrderMigrationIntegrationTest {

    private static final Map<String, String> PLACEHOLDERS = Map.of(
            "app_role", "accessflow",
            "audit_role", "accessflow_audit",
            "rag_pgvector_dimensions", "1536");

    @SuppressWarnings("resource")
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg18")
            .withInitScript("db/test-init-audit-roles.sql");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateToTheVersionBeforeV177() {
        postgres.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        flyway("176").migrate();
    }

    @AfterAll
    static void stopContainer() {
        postgres.stop();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(PLACEHOLDERS)
                .target(target)
                .load();
    }

    @Test
    void v177BackfillsDistinctContiguousSortOrdersPerPipelineThenEnforcesUniqueness() {
        var payments = pipeline("payments-api");
        var billing = pipeline("billing-api");
        // Pre-#877 reality: everything at 0 unless an admin typed a number. The tie-break is the
        // name, so "dev" < "staging" among the zeros; "production" already sits above them at 5.
        var paymentsStaging = environment(payments, "staging", 0);
        var paymentsDev = environment(payments, "dev", 0);
        var paymentsProd = environment(payments, "production", 5);
        var billingProd = environment(billing, "production", 0);
        var billingCanary = environment(billing, "canary", 0);
        assertThat(columnExists("datasource_id")).isFalse();

        flyway("177").migrate();

        assertThat(columnExists("datasource_id")).isTrue();
        assertThat(ladder(payments)).containsExactly("dev=0", "staging=1", "production=2");
        assertThat(ladder(billing)).containsExactly("canary=0", "production=1");
        assertThat(sortOrderOf(paymentsDev)).isZero();
        assertThat(sortOrderOf(paymentsStaging)).isEqualTo(1);
        assertThat(sortOrderOf(paymentsProd)).isEqualTo(2);
        assertThat(sortOrderOf(billingCanary)).isZero();
        assertThat(sortOrderOf(billingProd)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT datasource_id FROM deployment_environments", UUID.class))
                .hasSize(5).containsOnlyNulls();

        // The constraint the backfill made possible: a sibling on a taken position is refused …
        assertThatThrownBy(() -> environment(payments, "qa", 1))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_deployment_environments_pipeline_sort_order");
        // … while another pipeline may reuse it, and a free position still appends.
        environment(billing, "qa", 2);
        environment(payments, "qa", 3);
        assertThat(ladder(payments)).containsExactly("dev=0", "staging=1", "production=2", "qa=3");
    }

    private static UUID pipeline(String name) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deployment_pipelines (id, organization_id, name, provider)
                VALUES (?, ?, ?, 'GITHUB_ACTIONS'::pipeline_provider)
                """, id, UUID.randomUUID(), name + "-" + id);
        return id;
    }

    private static UUID environment(UUID pipelineId, String name, int sortOrder) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deployment_environments (id, pipeline_id, name, sort_order)
                VALUES (?, ?, ?, ?)
                """, id, pipelineId, name, sortOrder);
        return id;
    }

    private static List<String> ladder(UUID pipelineId) {
        return jdbc.query("""
                SELECT name, sort_order FROM deployment_environments
                WHERE pipeline_id = ? ORDER BY sort_order
                """, (rs, i) -> rs.getString("name") + "=" + rs.getInt("sort_order"), pipelineId);
    }

    private static int sortOrderOf(UUID environmentId) {
        return jdbc.queryForObject(
                "SELECT sort_order FROM deployment_environments WHERE id = ?", Integer.class, environmentId);
    }

    private static boolean columnExists(String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                               WHERE table_name = 'deployment_environments' AND column_name = ?)
                """, Boolean.class, column));
    }
}
