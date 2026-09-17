package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.bootstrap.internal.spec.ServiceAccountSpec;
import com.bablsoft.accessflow.core.api.OrganizationProvisioningService;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountAdminService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyBootstrapDeclaredException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountLookupService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link ServiceAccountReconciler} against the real database (#868): a declared account
 * comes out typed {@code SERVICE_ACCOUNT} with a {@code BOOTSTRAP}-managed detail row, a spec
 * change re-asserts the type without touching UI-owned fields, and an unchanged spec still
 * short-circuits on the fingerprint with zero writes.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ServiceAccountReconcilerIntegrationTest {

    @Autowired ServiceAccountReconciler reconciler;
    @Autowired OrganizationProvisioningService organizationProvisioningService;
    @Autowired UserQueryService userQueryService;
    @Autowired ServiceAccountLookupService serviceAccountLookupService;
    @Autowired ServiceAccountAdminService serviceAccountAdminService;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private String email;

    @BeforeEach
    void provisionOrganization() {
        var suffix = UUID.randomUUID();
        organizationId = organizationProvisioningService.create("sa-reconcile-" + suffix, "sa-reconcile-" + suffix);
        email = "ci-" + suffix + "@example.com";
    }

    @AfterEach
    void cleanup() {
        // bootstrap_state has no FKs by design, so it must go explicitly; users cascades
        // api_keys and service_accounts.
        jdbcTemplate.update("DELETE FROM bootstrap_state WHERE organization_id = ?", organizationId);
        jdbcTemplate.update("DELETE FROM users WHERE organization_id = ?", organizationId);
        jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", organizationId);
    }

    @Test
    void createTypesTheUserAsABootstrapManagedServiceAccount() {
        var userId = reconciler.reconcile(organizationId, List.of(spec("af_first_key"))).get(email);

        var user = userQueryService.findById(userId).orElseThrow();
        assertThat(user.principalType()).isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        assertThat(user.role()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT principal_type::text FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("SERVICE_ACCOUNT");

        var account = serviceAccountLookupService.findByUserId(userId).orElseThrow();
        assertThat(account.organizationId()).isEqualTo(organizationId);
        assertThat(account.managedBy()).isEqualTo(ServiceAccountSource.BOOTSTRAP);
        assertThat(account.mcpToolAllowList()).isNull();
        assertThat(account.rateLimitPerMinute()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT managed_by::text FROM service_accounts WHERE user_id = ?", String.class, userId))
                .isEqualTo("BOOTSTRAP");
    }

    @Test
    void unchangedSpecShortCircuitsOnTheFingerprintWithoutWriting() {
        var userId = reconciler.reconcile(organizationId, List.of(spec("af_first_key"))).get(email);
        var stampedAt = stateUpdatedAt(userId);
        // importOrUpdate unconditionally clears revoked_at on a re-import, so a key that is still
        // revoked after the second run proves the key import never ran.
        jdbcTemplate.update("UPDATE api_keys SET revoked_at = now() WHERE user_id = ?", userId);
        // Likewise a UI-owned edit must survive: ensureRegistered never touches it anyway, but the
        // short-circuit must not even get that far.
        jdbcTemplate.update("UPDATE service_accounts SET description = 'edited in the UI' WHERE user_id = ?", userId);

        var again = reconciler.reconcile(organizationId, List.of(spec("af_first_key"))).get(email);

        assertThat(again).isEqualTo(userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM api_keys WHERE user_id = ?", OffsetDateTime.class, userId)).isNotNull();
        assertThat(stateUpdatedAt(userId)).isEqualTo(stampedAt);
        assertThat(serviceAccountLookupService.findByUserId(userId).orElseThrow().description())
                .isEqualTo("edited in the UI");
    }

    @Test
    void changedSpecReassertsTheTypeAndKeepsUiOwnedFields() {
        var userId = reconciler.reconcile(organizationId, List.of(spec("af_first_key"))).get(email);
        // Simulate an install where the discriminator drifted and an admin edited the UI fields.
        jdbcTemplate.update("UPDATE users SET principal_type = 'HUMAN' WHERE id = ?", userId);
        jdbcTemplate.update("UPDATE service_accounts SET description = 'owned by the UI', "
                + "mcp_tool_allow_list = ARRAY['validate_sql']::text[], rate_limit_per_day = 7 "
                + "WHERE user_id = ?", userId);

        var again = reconciler.reconcile(organizationId, List.of(spec("af_rotated_key"))).get(email);

        assertThat(again).isEqualTo(userId);
        assertThat(userQueryService.findById(userId).orElseThrow().principalType())
                .isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        var account = serviceAccountLookupService.findByUserId(userId).orElseThrow();
        assertThat(account.managedBy()).isEqualTo(ServiceAccountSource.BOOTSTRAP);
        assertThat(account.description()).isEqualTo("owned by the UI");
        assertThat(account.mcpToolAllowList()).containsExactly("validate_sql");
        assertThat(account.rateLimitPerDay()).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM api_keys WHERE user_id = ?", OffsetDateTime.class, userId)).isNull();
    }

    @Test
    void declaredKeyRevokeIsRefusedRatherThanUndoneByTheNextReconcile() {
        var userId = reconciler.reconcile(organizationId, List.of(spec("af_first_key"))).get(email);
        var keyId = declaredKeyId(userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT bootstrap_declared FROM api_keys WHERE id = ?", Boolean.class, keyId)).isTrue();

        // #871: the admin surface refuses, and says so, instead of a revoke that the next changed
        // reconcile would silently undo through importOrUpdate's setRevokedAt(null).
        assertThatThrownBy(() -> serviceAccountAdminService.revokeKey(organizationId, userId, keyId))
                .isInstanceOf(ServiceAccountKeyBootstrapDeclaredException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM api_keys WHERE id = ?", OffsetDateTime.class, keyId)).isNull();

        // A changed spec re-imports the same row: still unrevoked, still declared, same id.
        var again = reconciler.reconcile(organizationId, List.of(spec("af_rotated_key"))).get(email);
        assertThat(again).isEqualTo(userId);
        assertThat(declaredKeyId(userId)).isEqualTo(keyId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM api_keys WHERE id = ?", OffsetDateTime.class, keyId)).isNull();
    }

    @Test
    void renamingTheDeclaredKeyDemotesThePreviousRowToARevocableKey() {
        var userId = reconciler.reconcile(organizationId, List.of(spec("af_first_key"))).get(email);
        var oldKeyId = declaredKeyId(userId);

        reconciler.reconcile(organizationId, List.of(new ServiceAccountSpec(email, "CI runner",
                UserRoleType.REVIEWER, "terraform-v2", "af_second_key", null))).get(email);

        var newKeyId = declaredKeyId(userId);
        assertThat(newKeyId).isNotEqualTo(oldKeyId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT bootstrap_declared FROM api_keys WHERE id = ?", Boolean.class, oldKeyId)).isFalse();
        // At most one declared key per account — and the demoted one is an ordinary key now.
        serviceAccountAdminService.revokeKey(organizationId, userId, oldKeyId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM api_keys WHERE id = ?", OffsetDateTime.class, oldKeyId)).isNotNull();
    }

    private UUID declaredKeyId(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM api_keys WHERE user_id = ? AND bootstrap_declared", UUID.class, userId);
    }

    private ServiceAccountSpec spec(String apiKey) {
        return new ServiceAccountSpec(email, "CI runner", UserRoleType.REVIEWER, "terraform", apiKey, null);
    }

    private OffsetDateTime stateUpdatedAt(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM bootstrap_state WHERE resource_type = 'SERVICE_ACCOUNT' AND resource_id = ?",
                OffsetDateTime.class, userId);
    }
}
