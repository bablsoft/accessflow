package com.bablsoft.accessflow.core.internal.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link PgVectorFlywayConfiguration}'s migration strategy directly against a <b>plain</b>
 * (non-pgvector) Postgres so the degraded code paths are exercised deterministically without a full
 * Spring context (AF-336): explicit disable, auto-provision off, and idempotent re-runs. The
 * normal (pgvector-available) path is covered by the pgvector-backed integration tests.
 */
class PgVectorFlywayConfigurationIntegrationTest {

    @SuppressWarnings("resource")
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withInitScript("db/test-init-audit-roles-no-pgvector.sql");

    private static final Map<String, String> PLACEHOLDERS = Map.of(
            "app_role", "accessflow",
            "audit_role", "accessflow_audit",
            "rag_pgvector_dimensions", "1536");

    private JdbcTemplate jdbc;

    @BeforeAll
    static void startContainer() {
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        postgres.stop();
    }

    @BeforeEach
    void resetSchema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP SCHEMA IF EXISTS public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("GRANT USAGE, CREATE ON SCHEMA public TO accessflow");
        jdbc.execute("GRANT USAGE, CREATE ON SCHEMA public TO accessflow_audit");
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(PLACEHOLDERS)
                .load();
    }

    private FlywayMigrationStrategy strategy(boolean enabled, boolean autoProvision,
                                             DefaultPgVectorAvailability availability) {
        return new PgVectorFlywayConfiguration().pgVectorAwareFlywayMigrationStrategy(
                new PgVectorProperties(enabled, autoProvision), availability);
    }

    @Test
    void degradesAndCreatesKnowledgeDocumentWhenExtensionUnavailable() {
        var availability = new DefaultPgVectorAvailability();

        strategy(true, true, availability).migrate(flyway());

        assertThat(availability.isAvailable()).isFalse();
        assertThat(extensionCount()).isZero();
        assertThat(tableExists("knowledge_document")).isTrue();
        assertThat(tableExists("vector_store")).isFalse();
        assertThat(migrationApplied("69")).isTrue();
        assertThat(migrationApplied("70")).isTrue();
        assertThat(migrationApplied("73")).isTrue();
    }

    @Test
    void secondInvocationIsIdempotent() {
        strategy(true, true, new DefaultPgVectorAvailability()).migrate(flyway());

        var availability = new DefaultPgVectorAvailability();
        strategy(true, true, availability).migrate(flyway());

        assertThat(availability.isAvailable()).isFalse();
        assertThat(tableExists("knowledge_document")).isTrue();
        assertThat(tableExists("vector_store")).isFalse();
        // V69 recorded exactly once — the second run validated the existing row, it did not re-insert.
        assertThat(migrationRowCount("69")).isEqualTo(1);
    }

    @Test
    void degradesWhenExplicitlyDisabled() {
        var availability = new DefaultPgVectorAvailability();

        strategy(false, true, availability).migrate(flyway());

        assertThat(availability.isAvailable()).isFalse();
        assertThat(tableExists("knowledge_document")).isTrue();
        assertThat(tableExists("vector_store")).isFalse();
    }

    @Test
    void degradesWhenAutoProvisionDisabled() {
        var availability = new DefaultPgVectorAvailability();

        strategy(true, false, availability).migrate(flyway());

        assertThat(availability.isAvailable()).isFalse();
        assertThat(tableExists("knowledge_document")).isTrue();
        assertThat(tableExists("vector_store")).isFalse();
    }

    private boolean tableExists(String name) {
        var count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    private int extensionCount() {
        var count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        return count == null ? 0 : count;
    }

    private boolean migrationApplied(String version) {
        return migrationRowCount(version) > 0;
    }

    private int migrationRowCount(String version) {
        var count = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success = true",
                Integer.class, version);
        return count == null ? 0 : count;
    }
}
