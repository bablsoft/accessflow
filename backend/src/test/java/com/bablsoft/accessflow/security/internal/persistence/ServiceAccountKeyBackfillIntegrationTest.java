package com.bablsoft.accessflow.security.internal.persistence;

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
 * V175's backfill (#871) flags the keys an install's bootstrap-managed service accounts already
 * hold — every key of a {@code managed_by = BOOTSTRAP} account, and nothing owned by a UI-managed
 * account or a human. Fresh containers migrate an empty schema, so the statement would otherwise
 * run against zero rows; this test seeds the pre-migration shape and replays the migration's own
 * UPDATE verbatim, read from the migration file (the V173 backfill test's precedent).
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ServiceAccountKeyBackfillIntegrationTest {

    private static final String MIGRATION = "db/migration/V175__add_api_key_bootstrap_declared.sql";

    @Autowired JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID bootstrapAccount;
    private UUID uiAccount;
    private UUID human;
    private UUID bootstrapKey;
    private UUID bootstrapSecondKey;
    private UUID uiKey;
    private UUID humanKey;

    @BeforeEach
    void seed() {
        organizationId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                organizationId, "sa-key-backfill", "sa-key-backfill-" + organizationId);
        bootstrapAccount = seedUser("SERVICE_ACCOUNT");
        uiAccount = seedUser("SERVICE_ACCOUNT");
        human = seedUser("HUMAN");
        seedDetail(bootstrapAccount, "BOOTSTRAP");
        seedDetail(uiAccount, "UI");
        bootstrapKey = seedKey(bootstrapAccount, "terraform");
        bootstrapSecondKey = seedKey(bootstrapAccount, "extra");
        uiKey = seedKey(uiAccount, "ci");
        humanKey = seedKey(human, "personal");
    }

    @AfterEach
    void cleanup() {
        // users cascades api_keys and service_accounts.
        jdbcTemplate.update("DELETE FROM users WHERE organization_id = ?", organizationId);
        jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", organizationId);
    }

    @Test
    void flagsEveryKeyOfABootstrapManagedAccountAndNothingElse() throws IOException {
        // Scoped to the seeded keys: the shipped statement filters on service_accounts only
        // (correct for a one-shot migration), but this replay runs in the shared Testcontainers
        // database and must not rewrite other test classes' rows. The predicate under test — the
        // EXISTS over service_accounts — is still taken verbatim.
        var scope = " AND k.id IN (?, ?, ?, ?);";
        jdbcTemplate.update(statement().replace(";", scope),
                bootstrapKey, bootstrapSecondKey, uiKey, humanKey);

        // Deliberately conservative: every key of the BOOTSTRAP account is marked, not only the
        // one the YAML happens to name — the migration cannot know the name, and over-marking
        // self-heals on the next changed reconcile.
        assertThat(declared(bootstrapKey)).isTrue();
        assertThat(declared(bootstrapSecondKey)).isTrue();
        assertThat(declared(uiKey)).isFalse();
        assertThat(declared(humanKey)).isFalse();
    }

    /** The one UPDATE from V175, taken verbatim so the test cannot drift from what ships. */
    private static String statement() throws IOException {
        var sql = new String(new ClassPathResource(MIGRATION).getContentAsByteArray(),
                StandardCharsets.UTF_8);
        var prefix = "UPDATE api_keys";
        assertThat(sql.split(prefix, -1).length - 1)
                .as("V175 must contain exactly one '%s' backfill statement — "
                        + "this test replays only the first", prefix)
                .isEqualTo(1);
        var start = sql.indexOf(prefix);
        return sql.substring(start, sql.indexOf(';', start) + 1);
    }

    private boolean declared(UUID keyId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT bootstrap_declared FROM api_keys WHERE id = ?", Boolean.class, keyId));
    }

    private UUID seedUser(String principalType) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, organization_id, email, role, principal_type) "
                        + "VALUES (?, ?, ?, 'ADMIN'::user_role_type, ?::principal_type)",
                id, organizationId, id + "@sa-key-backfill.example.com", principalType);
        return id;
    }

    private void seedDetail(UUID userId, String managedBy) {
        jdbcTemplate.update("INSERT INTO service_accounts (user_id, organization_id, managed_by) "
                        + "VALUES (?, ?, ?::service_account_source)",
                userId, organizationId, managedBy);
    }

    private UUID seedKey(UUID userId, String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO api_keys (id, organization_id, user_id, name, key_prefix, key_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, organizationId, userId, name, "af_seed" + name, "hash-" + id);
        return id;
    }
}
