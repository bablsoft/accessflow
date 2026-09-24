package com.bablsoft.accessflow.core.internal.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upgrade test for V188 (#1092): drives Flyway to V187 on a private container, seeds estimates
 * whose plans carry inlined row-security values, then applies V188. V187 belongs to #937;
 * targeting it before that migration lands simply stops at V186.
 */
class QueryEstimatePlanRedactionMigrationIntegrationTest {

    private static final Map<String, String> PLACEHOLDERS = Map.of(
            "app_role", "accessflow",
            "audit_role", "accessflow_audit",
            "rag_pgvector_dimensions", "1536");

    @SuppressWarnings("resource")
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg18")
            .withInitScript("db/test-init-audit-roles.sql");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateToTheVersionBeforeV188() {
        postgres.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        flyway("187").migrate();
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
    void v188StripsEveryNodeDetailAndRawPlanButKeepsPlanFigures() {
        var nested = estimate("""
                {"operation": "Nested Loop", "target": null, "estimated_rows": 10.0,
                 "estimated_cost": 8.5, "detail": "(o.user_id = u.id)",
                 "children": [
                   {"operation": "Index Scan", "target": "users", "estimated_rows": 1.0,
                    "estimated_cost": 2.0,
                    "detail": "((email)::text = 'dana@acme.example'::text)", "children": []},
                   {"operation": "Seq Scan", "target": "orders", "estimated_rows": 9.0,
                    "estimated_cost": 4.0, "detail": null, "children": []}]}
                """, "[{\"Plan\": {\"Filter\": \"'dana@acme.example'\"}}]");
        var rawOnly = estimate(null, "EXPLAIN text 'dana@acme.example'");
        var unsupported = estimate(null, null);

        flyway("188").migrate();

        String plan = jdbc.queryForObject(
                "SELECT plan::text FROM query_estimates WHERE id = ?", String.class, nested);
        assertThat(plan).doesNotContain("dana@acme.example").doesNotContain("o.user_id");
        var root = JsonMapper.builder().build().readTree(plan);
        assertThat(root.get("detail").isNull()).isTrue();
        assertThat(root.get("operation").asString()).isEqualTo("Nested Loop");
        assertThat(root.get("estimated_cost").asDouble()).isEqualTo(8.5);
        assertThat(root.get("children")).hasSize(2);
        assertThat(root.get("children").get(0).get("operation").asString())
                .isEqualTo("Index Scan");
        assertThat(root.get("children").get(0).get("target").asString()).isEqualTo("users");
        assertThat(root.get("children").get(0).get("detail").isNull()).isTrue();
        assertThat(root.get("children").get(1).get("operation").asString())
                .isEqualTo("Seq Scan");
        assertThat(rawPlan(nested)).isNull();
        assertThat(rawPlan(rawOnly)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM query_estimates WHERE id = ?",
                Long.class, unsupported)).isEqualTo(1L);
    }

    private static String rawPlan(UUID id) {
        return jdbc.queryForObject("SELECT raw_plan FROM query_estimates WHERE id = ?",
                String.class, id);
    }

    /** Seeds an estimate without its FK chain — the migration only rewrites query_estimates. */
    private static UUID estimate(String planJson, String rawPlan) {
        var id = UUID.randomUUID();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO query_estimates (id, query_request_id, supported, plan, raw_plan)
                    VALUES (?, ?, true, CAST(? AS JSONB), ?)
                    """)) {
                insert.setObject(1, id);
                insert.setObject(2, UUID.randomUUID());
                insert.setString(3, planJson);
                insert.setString(4, rawPlan);
                insert.executeUpdate();
            }
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        });
        return id;
    }
}
