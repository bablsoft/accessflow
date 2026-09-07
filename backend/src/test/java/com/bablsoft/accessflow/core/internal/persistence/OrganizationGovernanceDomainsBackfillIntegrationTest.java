package com.bablsoft.accessflow.core.internal.persistence;

import com.bablsoft.accessflow.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V158's backfill (AF-898) is the load-bearing half of the migration: an upgraded tenant that
 * already uses API or deployment governance must never get its onboarding checklist re-opened with
 * steps it has demonstrably satisfied. Fresh containers migrate an empty schema, so the statement
 * would otherwise run against zero rows and prove nothing — this test seeds the pre-migration
 * shape and replays the migration's own UPDATE verbatim, read from the migration file.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class OrganizationGovernanceDomainsBackfillIntegrationTest {

    private static final String MIGRATION =
            "db/migration/V158__organization_governance_domains.sql";

    @Autowired JdbcTemplate jdbcTemplate;

    private UUID withConnector;
    private UUID withPipeline;
    private UUID withBoth;
    private UUID withNeither;

    @BeforeEach
    void seed() {
        withConnector = seedOrganization("backfill-connector");
        withPipeline = seedOrganization("backfill-pipeline");
        withBoth = seedOrganization("backfill-both");
        withNeither = seedOrganization("backfill-neither");

        seedConnector(withConnector);
        seedConnector(withBoth);
        seedPipeline(withPipeline);
        seedPipeline(withBoth);

        // seedOrganization inserts only (id, name, slug), so both columns hold their
        // DEFAULT false here — the same state the migration's UPDATE would find.
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM api_connectors WHERE organization_id IN (?, ?, ?, ?)",
                withConnector, withPipeline, withBoth, withNeither);
        jdbcTemplate.update("DELETE FROM deployment_pipelines WHERE organization_id IN (?, ?, ?, ?)",
                withConnector, withPipeline, withBoth, withNeither);
        jdbcTemplate.update("DELETE FROM organizations WHERE id IN (?, ?, ?, ?)",
                withConnector, withPipeline, withBoth, withNeither);
    }

    @Test
    void backfillsBothFlagsFromExistingConnectorsAndPipelines() throws IOException {
        // Scoped to the four seeded orgs: the shipped statement has no WHERE (correct for a
        // one-shot migration), but this replay runs in the shared Testcontainers database and
        // must not rewrite other test classes' rows. The SET expressions — the part under test —
        // are still taken verbatim.
        jdbcTemplate.update(backfillStatement().replace(";", " WHERE o.id IN (?, ?, ?, ?)"),
                withConnector, withPipeline, withBoth, withNeither);

        assertThat(flags(withConnector)).containsExactly(true, false);
        assertThat(flags(withPipeline)).containsExactly(false, true);
        assertThat(flags(withBoth)).containsExactly(true, true);
        assertThat(flags(withNeither)).containsExactly(false, false);
    }

    /** The UPDATE from V158, taken verbatim so the test cannot drift from the shipped statement. */
    private static String backfillStatement() throws IOException {
        var sql = new String(new ClassPathResource(MIGRATION).getContentAsByteArray(),
                StandardCharsets.UTF_8);
        // Guard the extraction: it takes the FIRST UPDATE up to the FIRST semicolon, so a
        // migration later split into two statements would silently under-cover and stay green.
        assertThat(sql.split("UPDATE organizations", -1).length - 1)
                .as("V158 must contain exactly one backfill UPDATE — "
                        + "this test replays only the first")
                .isEqualTo(1);
        var start = sql.indexOf("UPDATE organizations");
        return sql.substring(start, sql.indexOf(';', start) + 1);
    }

    private Boolean[] flags(UUID organizationId) {
        return jdbcTemplate.queryForObject(
                "SELECT governs_apis, governs_deployments FROM organizations WHERE id = ?",
                (rs, rowNum) -> new Boolean[]{rs.getBoolean(1), rs.getBoolean(2)},
                organizationId);
    }

    private UUID seedOrganization(String prefix) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                id, prefix, prefix + "-" + id);
        return id;
    }

    private void seedConnector(UUID organizationId) {
        jdbcTemplate.update("""
                INSERT INTO api_connectors (id, organization_id, name, protocol, base_url)
                VALUES (?, ?, ?, 'REST'::api_protocol, 'https://api.test')
                """, UUID.randomUUID(), organizationId, "connector-" + UUID.randomUUID());
    }

    private void seedPipeline(UUID organizationId) {
        jdbcTemplate.update("""
                INSERT INTO deployment_pipelines (id, organization_id, name, provider)
                VALUES (?, ?, ?, 'GITHUB_ACTIONS'::pipeline_provider)
                """, UUID.randomUUID(), organizationId, "pipeline-" + UUID.randomUUID());
    }
}
