package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.RowLimitPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.RowLimitPolicyRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultRowLimitPolicyResolutionServiceTest {

    @Mock RowLimitPolicyRepository rowLimitPolicyRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupMembershipRepository membershipRepository;

    private DefaultRowLimitPolicyResolutionService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID user1 = UUID.randomUUID();
    private final UUID user2 = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultRowLimitPolicyResolutionService(rowLimitPolicyRepository,
                userRepository, membershipRepository);
        when(userRepository.findById(any())).thenReturn(Optional.empty());
        when(membershipRepository.findGroupIdsForUser(any())).thenReturn(List.of());
    }

    @Test
    void noPoliciesResolvesNothing() {
        stubPolicies();

        assertThat(resolve(user1, "crm.customer")).isEmpty();
        verify(userRepository, never()).findById(any());
    }

    @Test
    void emptyReferencedTablesResolvesNothingWithoutLoadingPolicies() {
        assertThat(service.resolve(orgId, datasourceId, user1, Set.of())).isEmpty();
        assertThat(service.resolve(orgId, datasourceId, user1, null)).isEmpty();
        verify(rowLimitPolicyRepository, never())
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(any(), any());
    }

    @Test
    void twoTablesOnOneDatasourceEnforceDifferentCapsForTheSameUser() {
        var customer = policy("crm", "customer", 1000);
        customer.setAppliesToUserIds(new UUID[]{user1});
        var orders = policy("crm", "orders", 200);
        orders.setAppliesToUserIds(new UUID[]{user1});
        stubPolicies(customer, orders);

        assertThat(resolve(user1, "crm.customer")).get()
                .satisfies(r -> assertThat(r.maxRows()).isEqualTo(1000));
        assertThat(resolve(user1, "crm.orders")).get()
                .satisfies(r -> assertThat(r.maxRows()).isEqualTo(200));
    }

    @Test
    void twoUsersEnforceDifferentCapsOnTheSameTable() {
        var forUser1 = policy("crm", "customer", 1000);
        forUser1.setAppliesToUserIds(new UUID[]{user1});
        var forUser2 = policy("crm", "customer", 300);
        forUser2.setAppliesToUserIds(new UUID[]{user2});
        stubPolicies(forUser1, forUser2);

        var r1 = resolve(user1, "crm.customer").orElseThrow();
        var r2 = resolve(user2, "crm.customer").orElseThrow();

        assertThat(r1.maxRows()).isEqualTo(1000);
        assertThat(r1.policyIds()).containsExactly(forUser1.getId());
        assertThat(r2.maxRows()).isEqualTo(300);
        assertThat(r2.policyIds()).containsExactly(forUser2.getId());
    }

    @Test
    void joinAcrossTwoPoliciedTablesTakesTheLowestCap() {
        var customer = policy("crm", "customer", 1000);
        var orders = policy("crm", "orders", 200);
        stubPolicies(customer, orders);

        var result = resolve(user1, "crm.customer", "crm.orders").orElseThrow();

        assertThat(result.maxRows()).isEqualTo(200);
        assertThat(result.policyIds()).containsExactly(orders.getId());
    }

    @Test
    void tiedLowestCapsReportEveryWinningPolicy() {
        var a = policy("crm", "customer", 50);
        var b = policy(null, "orders", 50);
        var c = policy("crm", "invoices", 80);
        stubPolicies(c, a, b);

        var result = resolve(user1, "crm.customer", "crm.orders", "crm.invoices").orElseThrow();

        assertThat(result.maxRows()).isEqualTo(50);
        assertThat(result.policyIds()).containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void unqualifiedReferenceMatchesAQualifiedPolicy() {
        stubPolicies(policy("crm", "customer", 10));

        assertThat(resolve(user1, "customer")).isPresent();
    }

    @Test
    void databaseQualifiedReferenceStillMatchesASchemaQualifiedPolicy() {
        stubPolicies(policy("crm", "customer", 10));

        assertThat(resolve(user1, "mydb.crm.customer")).isPresent();
        assertThat(resolve(user1, "project.crm.customer")).isPresent();
        assertThat(resolve(user1, "mydb.billing.customer")).isEmpty();
        assertThat(resolve(user1, "mydb.xcrm.customer")).isEmpty();
    }

    @Test
    void referencesAreMatchedCaseInsensitively() {
        stubPolicies(policy("crm", "customer", 10));

        assertThat(resolve(user1, "CRM.Customer")).isPresent();
    }

    @Test
    void schemaLessPolicyMatchesTheTableInAnySchema() {
        stubPolicies(policy(null, "customer", 10));

        assertThat(resolve(user1, "crm.customer")).isPresent();
        assertThat(resolve(user1, "customer")).isPresent();
        assertThat(resolve(user1, "mydb.crm.customer")).isPresent();
    }

    @Test
    void differentSchemaOrTableDoesNotMatch() {
        stubPolicies(policy("crm", "customer", 10), policy(null, "orders", 5));

        assertThat(resolve(user1, "billing.customer")).isEmpty();
        assertThat(resolve(user1, "crm.customers")).isEmpty();
        assertThat(resolve(user1, "crm.preorders")).isEmpty();
    }

    @Test
    void dottedTableNamesCompareWhole() {
        stubPolicies(policy(null, "logs-2026.09", 10));

        assertThat(resolve(user1, "logs-2026.09")).isPresent();
        assertThat(resolve(user1, "logs-2026.10")).isEmpty();
    }

    @Test
    void roleScopedPolicyAppliesOnlyToThatRole() {
        var policy = policy("crm", "customer", 10);
        policy.setAppliesToRoles(new String[]{"analyst"});
        stubPolicies(policy);
        var analyst = new UserEntity();
        analyst.setId(user1);
        analyst.setRole(UserRoleType.ANALYST);
        when(userRepository.findById(user1)).thenReturn(Optional.of(analyst));

        assertThat(resolve(user1, "crm.customer")).isPresent();
        assertThat(resolve(user2, "crm.customer")).isEmpty();
    }

    @Test
    void groupScopedPolicyAppliesOnlyToGroupMembers() {
        var policy = policy("crm", "customer", 10);
        policy.setAppliesToGroupIds(new UUID[]{groupId});
        stubPolicies(policy);
        when(membershipRepository.findGroupIdsForUser(user1)).thenReturn(List.of(groupId));

        assertThat(resolve(user1, "crm.customer")).isPresent();
        assertThat(resolve(user2, "crm.customer")).isEmpty();
    }

    @Test
    void emptyArraysMeanEveryone() {
        var policy = policy("crm", "customer", 10);
        policy.setAppliesToRoles(new String[0]);
        policy.setAppliesToGroupIds(new UUID[0]);
        policy.setAppliesToUserIds(new UUID[0]);
        stubPolicies(policy);

        assertThat(resolve(user2, "crm.customer")).isPresent();
    }

    @Test
    void roleScopedPolicyIgnoresUnknownUserAndNullEntries() {
        var policy = policy("crm", "customer", 10);
        policy.setAppliesToRoles(new String[]{null, "ADMIN"});
        stubPolicies(policy);

        assertThat(resolve(user1, "crm.customer")).isEmpty();
    }

    private Optional<com.bablsoft.accessflow.core.api.AppliedRowLimit> resolve(UUID userId,
                                                                               String... tables) {
        return service.resolve(orgId, datasourceId, userId, Set.of(tables));
    }

    private void stubPolicies(RowLimitPolicyEntity... policies) {
        when(rowLimitPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(orgId, datasourceId))
                .thenReturn(List.of(policies));
    }

    private RowLimitPolicyEntity policy(String schema, String table, int maxRows) {
        var entity = new RowLimitPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setSchemaName(schema);
        entity.setTableName(table);
        entity.setMaxRows(maxRows);
        return entity;
    }
}
