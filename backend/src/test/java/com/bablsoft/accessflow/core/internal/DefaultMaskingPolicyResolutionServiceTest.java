package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.MaskingPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.MaskingPolicyRepository;
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
class DefaultMaskingPolicyResolutionServiceTest {

    @Mock MaskingPolicyRepository maskingPolicyRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupMembershipRepository membershipRepository;

    private DefaultMaskingPolicyResolutionService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultMaskingPolicyResolutionService(maskingPolicyRepository,
                userRepository, membershipRepository, new ObjectMapper());
    }

    @Test
    void returnsEmptyAndSkipsLookupsWhenNoEnabledPolicies() {
        when(maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(orgId, datasourceId))
                .thenReturn(List.of());

        var result = service.resolveApplicable(orgId, datasourceId, userId);

        assertThat(result).isEmpty();
        verifyNoInteractions(userRepository, membershipRepository);
    }

    @Test
    void appliesPolicyAndParsesParamsWhenRequesterNotRevealed() {
        var policy = policy("public.users.email", MaskingStrategy.PARTIAL,
                "{\"visible_suffix\": 4}");
        policy.setRevealToRoles(new String[]{"ADMIN"});
        stubPolicies(policy);
        stubRole(UserRoleType.ANALYST);
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of());

        var result = service.resolveApplicable(orgId, datasourceId, userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().columnRef()).isEqualTo("public.users.email");
        assertThat(result.getFirst().strategy()).isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(result.getFirst().params()).containsEntry("visible_suffix", "4");
        assertThat(result.getFirst().policyId()).isEqualTo(policy.getId());
    }

    @Test
    void skipsPolicyWhenRevealedByRole() {
        var policy = policy("email", MaskingStrategy.HASH, "{}");
        policy.setRevealToRoles(new String[]{"analyst"});
        stubPolicies(policy);
        stubRole(UserRoleType.ANALYST);
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of());

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).isEmpty();
    }

    @Test
    void skipsPolicyWhenRevealedByUserId() {
        var policy = policy("email", MaskingStrategy.HASH, "{}");
        policy.setRevealToUserIds(new UUID[]{userId});
        stubPolicies(policy);
        stubRole(UserRoleType.READONLY);
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of());

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).isEmpty();
    }

    @Test
    void skipsPolicyWhenRevealedByGroupMembership() {
        var groupId = UUID.randomUUID();
        var policy = policy("email", MaskingStrategy.HASH, "{}");
        policy.setRevealToGroupIds(new UUID[]{groupId});
        stubPolicies(policy);
        stubRole(UserRoleType.READONLY);
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).isEmpty();
    }

    @Test
    void appliesWithEmptyParamsWhenJsonMalformed() {
        var policy = policy("email", MaskingStrategy.FULL, "not-json");
        stubPolicies(policy);
        stubRole(UserRoleType.ANALYST);
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of());

        var result = service.resolveApplicable(orgId, datasourceId, userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().params()).isEmpty();
    }

    @Test
    void appliesWhenRequesterUserMissing() {
        var policy = policy("email", MaskingStrategy.FULL, "{}");
        policy.setRevealToRoles(new String[]{"ADMIN"});
        stubPolicies(policy);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of());

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).hasSize(1);
    }

    private void stubPolicies(MaskingPolicyEntity... policies) {
        when(maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(orgId, datasourceId))
                .thenReturn(List.of(policies));
    }

    private void stubRole(UserRoleType role) {
        var user = new UserEntity();
        user.setId(userId);
        user.setRole(role);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private MaskingPolicyEntity policy(String columnRef, MaskingStrategy strategy, String params) {
        var entity = new MaskingPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setColumnRef(columnRef);
        entity.setStrategy(strategy);
        entity.setStrategyParams(params);
        entity.setEnabled(true);
        return entity;
    }
}
