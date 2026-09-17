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
 * V173's backfill (#868) types the service accounts an install already has: exactly the users the
 * bootstrap reconciler recorded in {@code bootstrap_state} as {@code SERVICE_ACCOUNT}, and no one
 * else. Fresh containers migrate an empty schema, so the statements would otherwise run against
 * zero rows and prove nothing — this test seeds the pre-migration shape and replays the migration's
 * own UPDATE and INSERT verbatim, read from the migration file.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ServiceAccountIdentityBackfillIntegrationTest {

    private static final String MIGRATION = "db/migration/V173__add_service_account_identity.sql";

    @Autowired JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID declaredAccount;
    private UUID ordinaryHuman;
    private UUID adminRecordedInState;

    @BeforeEach
    void seed() {
        organizationId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                organizationId, "sa-backfill", "sa-backfill-" + organizationId);
        declaredAccount = seedUser();
        ordinaryHuman = seedUser();
        adminRecordedInState = seedUser();
        // The reconciler's own bookkeeping: one SERVICE_ACCOUNT row keyed on the user id …
        seedState("SERVICE_ACCOUNT", declaredAccount);
        // … and an ADMIN_USER row, which must NOT be mistaken for a service account.
        seedState("ADMIN_USER", adminRecordedInState);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM bootstrap_state WHERE organization_id = ?", organizationId);
        // users cascades service_accounts.
        jdbcTemplate.update("DELETE FROM users WHERE organization_id = ?", organizationId);
        jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", organizationId);
    }

    @Test
    void typesExactlyTheBootstrapDeclaredAccountsAndLeavesEveryoneElseHuman() throws IOException {
        // Scoped to the three seeded users: the shipped statements filter on bootstrap_state only
        // (correct for a one-shot migration), but this replay runs in the shared Testcontainers
        // database and must not rewrite other test classes' rows. The predicate under test — the
        // EXISTS over bootstrap_state — is still taken verbatim.
        var scope = " AND u.id IN (?, ?, ?);";
        jdbcTemplate.update(statement("UPDATE users").replace(";", scope),
                declaredAccount, ordinaryHuman, adminRecordedInState);
        jdbcTemplate.update(statement("INSERT INTO service_accounts").replace(";", scope),
                declaredAccount, ordinaryHuman, adminRecordedInState);

        assertThat(principalType(declaredAccount)).isEqualTo("SERVICE_ACCOUNT");
        assertThat(principalType(ordinaryHuman)).isEqualTo("HUMAN");
        assertThat(principalType(adminRecordedInState)).isEqualTo("HUMAN");

        assertThat(managedBy(declaredAccount)).containsExactly("BOOTSTRAP");
        assertThat(organizationOf(declaredAccount)).containsExactly(organizationId);
        assertThat(managedBy(ordinaryHuman)).isEmpty();
        assertThat(managedBy(adminRecordedInState)).isEmpty();
    }

    /** One statement from V173, taken verbatim so the test cannot drift from what ships. */
    private static String statement(String prefix) throws IOException {
        var sql = new String(new ClassPathResource(MIGRATION).getContentAsByteArray(),
                StandardCharsets.UTF_8);
        // Guard the extraction: it takes the FIRST match up to the FIRST semicolon, so a migration
        // later split into two statements would silently under-cover and stay green.
        assertThat(sql.split(prefix, -1).length - 1)
                .as("V173 must contain exactly one '%s' backfill statement — "
                        + "this test replays only the first", prefix)
                .isEqualTo(1);
        var start = sql.indexOf(prefix);
        return sql.substring(start, sql.indexOf(';', start) + 1);
    }

    private UUID seedUser() {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, organization_id, email, role) "
                        + "VALUES (?, ?, ?, 'ADMIN'::user_role_type)",
                id, organizationId, id + "@sa-backfill.example.com");
        return id;
    }

    private void seedState(String resourceType, UUID resourceId) {
        jdbcTemplate.update("INSERT INTO bootstrap_state "
                        + "(id, organization_id, resource_type, resource_id, spec_fingerprint) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), organizationId, resourceType, resourceId, "f".repeat(64));
    }

    private String principalType(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT principal_type::text FROM users WHERE id = ?", String.class, userId);
    }

    private java.util.List<String> managedBy(UUID userId) {
        return jdbcTemplate.queryForList(
                "SELECT managed_by::text FROM service_accounts WHERE user_id = ?", String.class, userId);
    }

    private java.util.List<UUID> organizationOf(UUID userId) {
        return jdbcTemplate.queryForList(
                "SELECT organization_id FROM service_accounts WHERE user_id = ?", UUID.class, userId);
    }
}
