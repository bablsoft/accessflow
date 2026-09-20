package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationStatus;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountAdminView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountClearableField;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountIssuedKey;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountRotatedKey;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountWebModelsTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();

    @Test
    void responseCopiesEveryFieldAndMapsKeys() {
        var key = new ServiceAccountKeyView(UUID.randomUUID(), "ci", "af_ci", true, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1), Instant.EPOCH.plusSeconds(2), Instant.EPOCH.plusSeconds(3));
        var owner = UUID.randomUUID();
        var view = new ServiceAccountAdminView(ID, ORG, "bot@example.com", "Bot", UserRoleType.ANALYST, null,
                "custom", false, ServiceAccountSource.BOOTSTRAP, "d", owner, "owner@example.com", "Owner",
                List.of("validate_sql"), 1, 2, 1,
                Instant.EPOCH.plusSeconds(1), Instant.EPOCH.plusSeconds(4), Instant.EPOCH, Instant.EPOCH.plusSeconds(5),
                List.of(key));

        var response = ServiceAccountResponse.from(view);

        assertThat(response.id()).isEqualTo(ID);
        assertThat(response.email()).isEqualTo("bot@example.com");
        assertThat(response.displayName()).isEqualTo("Bot");
        assertThat(response.role()).isEqualTo(UserRoleType.ANALYST);
        assertThat(response.roleId()).isNull();
        assertThat(response.roleName()).isEqualTo("custom");
        assertThat(response.active()).isFalse();
        assertThat(response.managedBy()).isEqualTo(ServiceAccountSource.BOOTSTRAP);
        assertThat(response.description()).isEqualTo("d");
        assertThat(response.ownerUserId()).isEqualTo(owner);
        assertThat(response.ownerEmail()).isEqualTo("owner@example.com");
        assertThat(response.ownerDisplayName()).isEqualTo("Owner");
        assertThat(response.mcpToolAllowList()).containsExactly("validate_sql");
        assertThat(response.rateLimitPerMinute()).isEqualTo(1);
        assertThat(response.rateLimitPerDay()).isEqualTo(2);
        assertThat(response.activeApiKeyCount()).isEqualTo(1);
        assertThat(response.lastUsedAt()).isEqualTo(Instant.EPOCH.plusSeconds(1));
        assertThat(response.lastLoginAt()).isEqualTo(Instant.EPOCH.plusSeconds(4));
        assertThat(response.createdAt()).isEqualTo(Instant.EPOCH);
        assertThat(response.updatedAt()).isEqualTo(Instant.EPOCH.plusSeconds(5));
        assertThat(response.apiKeys()).singleElement().satisfies(k -> {
            assertThat(k.id()).isEqualTo(key.id());
            assertThat(k.name()).isEqualTo("ci");
            assertThat(k.keyPrefix()).isEqualTo("af_ci");
            assertThat(k.bootstrapDeclared()).isTrue();
            assertThat(k.createdAt()).isEqualTo(Instant.EPOCH);
            assertThat(k.lastUsedAt()).isEqualTo(Instant.EPOCH.plusSeconds(1));
            assertThat(k.expiresAt()).isEqualTo(Instant.EPOCH.plusSeconds(2));
            assertThat(k.revokedAt()).isEqualTo(Instant.EPOCH.plusSeconds(3));
        });
    }

    @Test
    void responseKeepsANullAllowListMeaningEveryTool() {
        var view = new ServiceAccountAdminView(ID, ORG, "e", "n", null, UUID.randomUUID(), "r", true,
                ServiceAccountSource.UI, null, null, null, null, null, null, null, 0, null, null, Instant.EPOCH,
                Instant.EPOCH, null);
        var response = ServiceAccountResponse.from(view);
        assertThat(response.mcpToolAllowList()).isNull();
        assertThat(response.apiKeys()).isEmpty();
    }

    @Test
    void mcpToolCatalogIsTheEnumWireNames() {
        var catalog = McpToolCatalogResponse.current();
        assertThat(catalog.tools()).hasSize(com.bablsoft.accessflow.serviceaccounts.api.McpToolName.values().length)
                .doesNotHaveDuplicates()
                .allSatisfy(name -> assertThat(name).matches("[a-z_]+"));
    }

    @Test
    void pageResponseCopiesThePageShape() {
        var view = new ServiceAccountAdminView(ID, ORG, "e", "n", null, null, "r", true,
                ServiceAccountSource.UI, null, null, null, null, List.of(), null, null, 0, null, null, Instant.EPOCH,
                Instant.EPOCH, List.of());
        var page = ServiceAccountPageResponse.from(new PageResponse<>(List.of(view), 2, 10, 21, 3));
        assertThat(page.content()).singleElement().extracting(ServiceAccountResponse::id).isEqualTo(ID);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(21);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void issuedAndRotatedResponsesCarryTheRawKeyOnce() {
        var key = new ServiceAccountKeyView(UUID.randomUUID(), "ci", "af_ci", false, Instant.EPOCH, null, null, null);
        var old = new ServiceAccountKeyView(UUID.randomUUID(), "old", "af_old", false, Instant.EPOCH, null,
                Instant.EPOCH.plusSeconds(60), null);

        var issued = ServiceAccountIssuedKeyResponse.from(new ServiceAccountIssuedKey(key, "af_raw"));
        assertThat(issued.rawKey()).isEqualTo("af_raw");
        assertThat(issued.apiKey().id()).isEqualTo(key.id());

        var rotated = ServiceAccountRotatedKeyResponse.from(new ServiceAccountRotatedKey(key, "af_raw2", old));
        assertThat(rotated.rawKey()).isEqualTo("af_raw2");
        assertThat(rotated.apiKey().name()).isEqualTo("ci");
        assertThat(rotated.supersededKey().id()).isEqualTo(old.id());
        assertThat(rotated.supersededKey().expiresAt()).isEqualTo(Instant.EPOCH.plusSeconds(60));
    }

    @Test
    void createRequestMapsToTheCommand() {
        var owner = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var command = new CreateServiceAccountRequest("bot@example.com", "Bot", UserRoleType.ANALYST, roleId,
                "d", owner, List.of("validate_sql"), 3, 4).toCommand();
        assertThat(command.email()).isEqualTo("bot@example.com");
        assertThat(command.displayName()).isEqualTo("Bot");
        assertThat(command.role()).isEqualTo(UserRoleType.ANALYST);
        assertThat(command.roleId()).isEqualTo(roleId);
        assertThat(command.description()).isEqualTo("d");
        assertThat(command.ownerUserId()).isEqualTo(owner);
        assertThat(command.mcpToolAllowList()).containsExactly("validate_sql");
        assertThat(command.rateLimitPerMinute()).isEqualTo(3);
        assertThat(command.rateLimitPerDay()).isEqualTo(4);
        assertThat(new CreateServiceAccountRequest("e", "n", null, null, null, null, null, null, null)
                .toCommand().mcpToolAllowList()).isNull();
    }

    @Test
    void updateRequestMapsToTheCommandAndListsPresentFields() {
        var owner = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var request = new UpdateServiceAccountRequest("Bot", UserRoleType.ANALYST, roleId, false, "d", owner,
                List.of(), 1, 2, null);
        var command = request.toCommand();
        assertThat(command.displayName()).isEqualTo("Bot");
        assertThat(command.role()).isEqualTo(UserRoleType.ANALYST);
        assertThat(command.roleId()).isEqualTo(roleId);
        assertThat(command.active()).isFalse();
        assertThat(command.description()).isEqualTo("d");
        assertThat(command.ownerUserId()).isEqualTo(owner);
        assertThat(command.mcpToolAllowList()).isEmpty();
        assertThat(command.rateLimitPerMinute()).isEqualTo(1);
        assertThat(command.rateLimitPerDay()).isEqualTo(2);
        assertThat(request.presentFields()).containsExactly("display_name", "role", "role_id", "active",
                "description", "owner_user_id", "mcp_tool_allow_list", "rate_limit_per_minute", "rate_limit_per_day");

        assertThat(request.clearedFields()).isEmpty();
        assertThat(command.clear()).isEmpty();
        assertThat(request.isClearDisjointFromValues()).isTrue();

        var empty = new UpdateServiceAccountRequest(null, null, null, null, null, null, null, null, null, null);
        assertThat(empty.presentFields()).isEmpty();
        assertThat(empty.clearedFields()).isEmpty();
        assertThat(empty.toCommand().mcpToolAllowList()).isNull();
        assertThat(empty.isClearDisjointFromValues()).isTrue();

        var cleared = new UpdateServiceAccountRequest(null, null, null, null, null, null, null, null, null,
                Set.of(ServiceAccountClearableField.RATE_LIMIT_PER_DAY, ServiceAccountClearableField.DESCRIPTION));
        assertThat(cleared.clearedFields()).containsExactly("description", "rate_limit_per_day");
        assertThat(cleared.toCommand().clear()).containsExactlyInAnyOrder(
                ServiceAccountClearableField.RATE_LIMIT_PER_DAY, ServiceAccountClearableField.DESCRIPTION);
        assertThat(cleared.isClearDisjointFromValues()).isTrue();
    }

    @Test
    void updateRequestRejectsAFieldThatIsBothSetAndCleared() {
        assertThat(new UpdateServiceAccountRequest(null, null, null, null, "d", null, null, null, null,
                Set.of(ServiceAccountClearableField.DESCRIPTION)).isClearDisjointFromValues()).isFalse();
        assertThat(new UpdateServiceAccountRequest(null, null, null, null, null, UUID.randomUUID(), null, null, null,
                Set.of(ServiceAccountClearableField.OWNER_USER_ID)).isClearDisjointFromValues()).isFalse();
        assertThat(new UpdateServiceAccountRequest(null, null, null, null, null, null, List.of(), null, null,
                Set.of(ServiceAccountClearableField.MCP_TOOL_ALLOW_LIST)).isClearDisjointFromValues()).isFalse();
        assertThat(new UpdateServiceAccountRequest(null, null, null, null, null, null, null, 1, null,
                Set.of(ServiceAccountClearableField.RATE_LIMIT_PER_MINUTE)).isClearDisjointFromValues()).isFalse();
        assertThat(new UpdateServiceAccountRequest(null, null, null, null, null, null, null, null, 1,
                Set.of(ServiceAccountClearableField.RATE_LIMIT_PER_DAY)).isClearDisjointFromValues()).isFalse();
    }

    @Test
    void keyRequestsMapToCommandsAndValidateTheGrace() {
        var expires = Instant.parse("2027-01-01T00:00:00Z");
        var issue = new IssueServiceAccountKeyRequest("ci", expires).toCommand();
        assertThat(issue.name()).isEqualTo("ci");
        assertThat(issue.expiresAt()).isEqualTo(expires);

        var rotate = new RotateServiceAccountKeyRequest("ci-2", expires, Duration.ofMinutes(5));
        assertThat(rotate.isGracePeriodPositive()).isTrue();
        assertThat(rotate.toCommand().name()).isEqualTo("ci-2");
        assertThat(rotate.toCommand().expiresAt()).isEqualTo(expires);
        assertThat(rotate.toCommand().gracePeriod()).isEqualTo(Duration.ofMinutes(5));

        assertThat(new RotateServiceAccountKeyRequest("x", null, null).isGracePeriodPositive()).isTrue();
        assertThat(new RotateServiceAccountKeyRequest("x", null, Duration.ZERO).isGracePeriodPositive()).isFalse();
        assertThat(new RotateServiceAccountKeyRequest("x", null, Duration.ofSeconds(-1)).isGracePeriodPositive())
                .isFalse();
    }
    @Test
    void delegationResponseCopiesEveryField() {
        var id = UUID.randomUUID();
        var principal = UUID.randomUUID();
        var grantedBy = UUID.randomUUID();
        var view = new ServiceAccountDelegationView(id, ORG, ID, "bot@example.com", principal,
                "alice@example.com", grantedBy, Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                Instant.EPOCH.plusSeconds(2), ServiceAccountDelegationStatus.REVOKED);

        var response = ServiceAccountDelegationResponse.from(view);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.serviceAccountUserId()).isEqualTo(ID);
        assertThat(response.serviceAccountEmail()).isEqualTo("bot@example.com");
        assertThat(response.principalUserId()).isEqualTo(principal);
        assertThat(response.principalEmail()).isEqualTo("alice@example.com");
        assertThat(response.grantedBy()).isEqualTo(grantedBy);
        assertThat(response.createdAt()).isEqualTo(Instant.EPOCH);
        assertThat(response.expiresAt()).isEqualTo(Instant.EPOCH.plusSeconds(1));
        assertThat(response.revokedAt()).isEqualTo(Instant.EPOCH.plusSeconds(2));
        assertThat(response.status()).isEqualTo(ServiceAccountDelegationStatus.REVOKED);
    }

    @Test
    void grantRequestsMapOntoTheCommandWithTheOtherPartyFromTheCaller() {
        var principal = UUID.randomUUID();
        var admin = new GrantDelegatedPrincipalRequest(principal, Instant.EPOCH).toCommand(ID);
        assertThat(admin.serviceAccountUserId()).isEqualTo(ID);
        assertThat(admin.principalUserId()).isEqualTo(principal);
        assertThat(admin.expiresAt()).isEqualTo(Instant.EPOCH);

        var self = new GrantMyServiceAccountDelegationRequest(ID, null).toCommand(principal);
        assertThat(self.serviceAccountUserId()).isEqualTo(ID);
        assertThat(self.principalUserId()).isEqualTo(principal);
        assertThat(self.expiresAt()).isNull();
    }
}
