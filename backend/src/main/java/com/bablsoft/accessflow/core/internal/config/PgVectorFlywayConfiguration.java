package com.bablsoft.accessflow.core.internal.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Makes the application start gracefully when the PostgreSQL {@code vector} (pgvector) extension is
 * unavailable (AF-336). The extension is a superuser-provisioned prerequisite that
 * {@code V69__create_knowledge_documents_and_vector_store.sql} assumes; without it that migration —
 * and therefore startup — fails with {@code type "vector" does not exist}.
 *
 * <p>This {@link FlywayMigrationStrategy} runs in place of the default {@code Flyway::migrate}:
 * <ol>
 *   <li>best-effort {@code CREATE EXTENSION IF NOT EXISTS vector} (a no-op when already present or
 *       when the role lacks privilege; the binary being absent is caught) when auto-provision is on;</li>
 *   <li>detect whether the {@code vector} type is usable;</li>
 *   <li><b>available</b> → migrate normally; additionally create {@code vector_store} if it is
 *       missing (self-heals a deployment where pgvector was installed only after a degraded start);</li>
 *   <li><b>unavailable</b> → migrate everything <i>except</i> V69 (recorded as applied without
 *       executing so subsequent boots validate cleanly), leaving the in-app PGVECTOR store
 *       uncreated. {@code knowledge_document} — a JPA-validated entity that must exist — is created
 *       by the idempotent {@code V73} migration instead.</li>
 * </ol>
 * The decision is published via {@link DefaultPgVectorAvailability} so the {@code ai} module fails
 * PGVECTOR RAG operations cleanly; the external QDRANT store is unaffected either way.
 */
@Configuration
@EnableConfigurationProperties(PgVectorProperties.class)
class PgVectorFlywayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PgVectorFlywayConfiguration.class);

    /** The vector-store migration whose {@code VECTOR(N)} column / HNSW index need pgvector. */
    private static final String VECTOR_STORE_MIGRATION_VERSION = "69";
    private static final int DEFAULT_DIMENSIONS = 1536;

    @Bean
    FlywayMigrationStrategy pgVectorAwareFlywayMigrationStrategy(PgVectorProperties properties,
                                                                DefaultPgVectorAvailability availability) {
        return flyway -> {
            var jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
            boolean available = false;
            if (properties.enabled()) {
                if (properties.autoProvision()) {
                    tryCreateExtension(jdbc);
                }
                available = vectorExtensionInstalled(jdbc);
            }

            if (available) {
                flyway.migrate();
                ensureVectorStore(jdbc, dimensions(flyway));
                log.info("pgvector extension available — in-app PGVECTOR RAG enabled");
            } else {
                if (properties.enabled()) {
                    log.warn("pgvector extension unavailable (not installed and not auto-provisionable) — "
                            + "in-app PGVECTOR RAG is disabled and the vector_store migration is skipped; "
                            + "external QDRANT RAG is unaffected. To enable it, install the 'vector' "
                            + "extension (a pgvector-enabled image or 'CREATE EXTENSION vector' as a "
                            + "superuser). See docs/09-deployment.md → \"pgvector for RAG\".");
                } else {
                    log.warn("pgvector disabled via accessflow.rag.pgvector.enabled=false — in-app "
                            + "PGVECTOR RAG is disabled and the vector_store migration is skipped; "
                            + "external QDRANT RAG is unaffected.");
                }
                migrateSkippingVectorStore(flyway, jdbc);
            }
            availability.set(available);
        };
    }

    private void tryCreateExtension(JdbcTemplate jdbc) {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (DataAccessException e) {
            log.warn("Best-effort 'CREATE EXTENSION IF NOT EXISTS vector' did not succeed: {}",
                    e.getMessage());
        }
    }

    private boolean vectorExtensionInstalled(JdbcTemplate jdbc) {
        var count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        return count != null && count > 0;
    }

    /**
     * Skip {@code V69} but apply everything else. {@code knowledge_document} (needed by Hibernate
     * {@code ddl-auto=validate}) is recreated by the idempotent {@code V73} migration; only the
     * pgvector-dependent {@code vector_store} is omitted.
     */
    private void migrateSkippingVectorStore(Flyway flyway, JdbcTemplate jdbc) {
        if (vectorStoreMigrationApplied(flyway, jdbc)) {
            // Already skipped on a prior boot (or normally applied) — nothing to omit.
            flyway.migrate();
            return;
        }
        var before = versionBefore(flyway, MigrationVersion.fromVersion(VECTOR_STORE_MIGRATION_VERSION));
        Flyway phase1 = Flyway.configure()
                .configuration(flyway.getConfiguration())
                .target(before)
                .load();
        phase1.migrate();
        recordMigrationSkipped(flyway, jdbc);
        flyway.migrate();
    }

    private boolean vectorStoreMigrationApplied(Flyway flyway, JdbcTemplate jdbc) {
        var table = flyway.getConfiguration().getTable();
        var historyExists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
        if (!Boolean.TRUE.equals(historyExists)) {
            return false;
        }
        var count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE version = ? AND success = true",
                Integer.class, VECTOR_STORE_MIGRATION_VERSION);
        return count != null && count > 0;
    }

    private MigrationVersion versionBefore(Flyway flyway, MigrationVersion ceiling) {
        return Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(v -> v != null && v.compareTo(ceiling) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException(
                        "No migration precedes V" + ceiling.getVersion() + " — cannot skip it safely"));
    }

    /**
     * Insert a {@code flyway_schema_history} row marking V69 as applied without executing it. Every
     * value comes from the resolved migration so {@code validateOnMigrate} on later boots matches
     * (checksum / description / type / script). The table name is the Flyway-configured constant —
     * not user input — and all values are bound parameters.
     */
    private void recordMigrationSkipped(Flyway flyway, JdbcTemplate jdbc) {
        var info = resolvedMigration(flyway, VECTOR_STORE_MIGRATION_VERSION);
        var table = flyway.getConfiguration().getTable();
        var rank = jdbc.queryForObject(
                "SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM " + table, Integer.class);
        jdbc.update("INSERT INTO " + table + " (installed_rank, version, description, type, script, "
                        + "checksum, installed_by, installed_on, execution_time, success) "
                        + "VALUES (?, ?, ?, ?, ?, ?, current_user, now(), 0, true)",
                rank, info.getVersion().getVersion(), info.getDescription(), info.getType().name(),
                info.getScript(), info.getChecksum());
        log.warn("Recorded {} as skipped in {} (pgvector unavailable); knowledge_document is created "
                + "by V73 and vector_store is omitted", info.getScript(), table);
    }

    private MigrationInfo resolvedMigration(Flyway flyway, String version) {
        return Arrays.stream(flyway.info().all())
                .filter(m -> m.getVersion() != null && version.equals(m.getVersion().getVersion()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Migration V" + version + " not found"));
    }

    /** Create {@code vector_store} when pgvector is present but the table is missing (added-later case). */
    private void ensureVectorStore(JdbcTemplate jdbc, int dimensions) {
        var exists = jdbc.queryForObject("SELECT to_regclass('public.vector_store') IS NOT NULL", Boolean.class);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        // `dimensions` is a parsed integer (not user input); the rest mirrors V69 verbatim.
        jdbc.execute("CREATE TABLE IF NOT EXISTS vector_store ("
                + "id UUID DEFAULT gen_random_uuid() PRIMARY KEY, content TEXT, metadata JSON, "
                + "embedding VECTOR(" + dimensions + "))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS vector_store_embedding_idx "
                + "ON vector_store USING HNSW (embedding vector_cosine_ops)");
        log.info("Created vector_store table (dimension {}) — pgvector became available after a "
                + "degraded start", dimensions);
    }

    private int dimensions(Flyway flyway) {
        var raw = flyway.getConfiguration().getPlaceholders().get("rag_pgvector_dimensions");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DIMENSIONS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_DIMENSIONS;
        }
    }
}
