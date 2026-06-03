package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.RowSecurityPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.RowSecurityPolicyRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultRowSecurityResolutionServiceTest {

    @Mock RowSecurityPolicyRepository rowSecurityPolicyRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupMembershipRepository membershipRepository;

    private DefaultRowSecurityResolutionService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultRowSecurityResolutionService(rowSecurityPolicyRepository,
                userRepository, membershipRepository, new ObjectMapper());
    }

    @Test
    void returnsEmptyAndSkipsLookupsWhenNoEnabledPolicies() {
        stubPolicies();

        var result = service.resolveApplicable(orgId, datasourceId, userId);

        assertThat(result).isEmpty();
        verifyNoInteractions(userRepository, membershipRepository);
    }

    @Test
    void emptyScopeAppliesToEveryoneAndResolvesLiteral() {
        stubPolicies(policy("orders", "region", RowSecurityOperator.EQUALS,
                RowSecurityValueType.LITERAL, "EU"));
        stubUser(UserRoleType.ANALYST, "a@x.io", "{}");
        stubGroupIds();

        var result = service.resolveApplicable(orgId, datasourceId, userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().tableRef()).isEqualTo("orders");
        assertThat(result.getFirst().columnName()).isEqualTo("region");
        assertThat(result.getFirst().operator()).isEqualTo(RowSecurityOperator.EQUALS);
        assertThat(result.getFirst().values()).containsExactly("EU");
    }

    @Test
    void resolvesUserAttributeVariable() {
        stubPolicies(varPolicy("user.region", RowSecurityOperator.EQUALS));
        stubUser(UserRoleType.ANALYST, "a@x.io", "{\"region\":\"EU\"}");
        stubGroupIds();

        assertThat(service.resolveApplicable(orgId, datasourceId, userId).getFirst().values())
                .containsExactly("EU");
    }

    @Test
    void resolvesBuiltInRoleEmailAndId() {
        stubUser(UserRoleType.REVIEWER, "rev@x.io", "{}");
        stubGroupIds();

        stubPolicies(varPolicy("user.role", RowSecurityOperator.EQUALS));
        assertThat(service.resolveApplicable(orgId, datasourceId, userId).getFirst().values())
                .containsExactly("REVIEWER");

        stubPolicies(varPolicy("user.email", RowSecurityOperator.EQUALS));
        assertThat(service.resolveApplicable(orgId, datasourceId, userId).getFirst().values())
                .containsExactly("rev@x.io");

        stubPolicies(varPolicy("user.id", RowSecurityOperator.EQUALS));
        assertThat(service.resolveApplicable(orgId, datasourceId, userId).getFirst().values())
                .containsExactly(userId.toString());
    }

    @Test
    void resolvesUserGroupsVariableToGroupNames() {
        stubPolicies(varPolicy("user.groups", RowSecurityOperator.IN));
        stubUser(UserRoleType.ANALYST, "a@x.io", "{}");
        stubGroupIds();
        when(membershipRepository.findGroupNamesForUser(userId))
                .thenReturn(List.of("analysts", "eu"));

        assertThat(service.resolveApplicable(orgId, datasourceId, userId).getFirst().values())
                .containsExactly("analysts", "eu");
    }

    @Test
    void missingAttributeDeniesWithEmptyValues() {
        stubPolicies(varPolicy("user.region", RowSecurityOperator.EQUALS));
        stubUser(UserRoleType.ANALYST, "a@x.io", "{}");
        stubGroupIds();

        assertThat(service.resolveApplicable(orgId, datasourceId, userId).getFirst().values())
                .isEmpty();
    }

    @Test
    void emptyGroupsDenyWithEmptyValues() {
        stubPolicies(varPolicy("user.groups", RowSecurityOperator.IN));
        stubUser(UserRoleType.ANALYST, "a@x.io", "{}");
        stubGroupIds();
        when(membershipRepository.findGroupNamesForUser(userId)).thenReturn(List.of());

        assertThat(service.resolveApplicable(orgId, datasourceId, userId).getFirst().values())
                .isEmpty();
    }

    @Test
    void roleScopeNarrowsToMatchingRole() {
        var policy = policy("orders", "region", RowSecurityOperator.EQUALS,
                RowSecurityValueType.LITERAL, "EU");
        policy.setAppliesToRoles(new String[]{"ADMIN"});
        stubPolicies(policy);
        stubUser(UserRoleType.ANALYST, "a@x.io", "{}");
        stubGroupIds();

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).isEmpty();
    }

    @Test
    void roleScopeAppliesWhenRoleMatches() {
        var policy = policy("orders", "region", RowSecurityOperator.EQUALS,
                RowSecurityValueType.LITERAL, "EU");
        policy.setAppliesToRoles(new String[]{"analyst"});
        stubPolicies(policy);
        stubUser(UserRoleType.ANALYST, "a@x.io", "{}");
        stubGroupIds();

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).hasSize(1);
    }

    @Test
    void userScopeAppliesWhenUserMatches() {
        var policy = policy("orders", "region", RowSecurityOperator.EQUALS,
                RowSecurityValueType.LITERAL, "EU");
        policy.setAppliesToUserIds(new UUID[]{userId});
        stubPolicies(policy);
        stubUser(UserRoleType.READONLY, "a@x.io", "{}");
        stubGroupIds();

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).hasSize(1);
    }

    @Test
    void groupScopeAppliesWhenGroupMatches() {
        var groupId = UUID.randomUUID();
        var policy = policy("orders", "region", RowSecurityOperator.EQUALS,
                RowSecurityValueType.LITERAL, "EU");
        policy.setAppliesToGroupIds(new UUID[]{groupId});
        stubPolicies(policy);
        stubUser(UserRoleType.READONLY, "a@x.io", "{}");
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).hasSize(1);
    }

    private void stubPolicies(RowSecurityPolicyEntity... policies) {
        when(rowSecurityPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(orgId, datasourceId))
                .thenReturn(List.of(policies));
    }

    private void stubUser(UserRoleType role, String email, String attributes) {
        var user = new UserEntity();
        user.setId(userId);
        user.setRole(role);
        user.setEmail(email);
        user.setAttributes(attributes);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private void stubGroupIds() {
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of());
    }

    private RowSecurityPolicyEntity varPolicy(String variable, RowSecurityOperator op) {
        return policy("orders", "region", op, RowSecurityValueType.VARIABLE, variable);
    }

    private RowSecurityPolicyEntity policy(String table, String column, RowSecurityOperator op,
                                           RowSecurityValueType valueType, String value) {
        var entity = new RowSecurityPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setTableName(table);
        entity.setColumnName(column);
        entity.setOperator(op);
        entity.setValueType(valueType);
        entity.setValueExpression(value);
        entity.setEnabled(true);
        return entity;
    }
}
