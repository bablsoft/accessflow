package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.ExportPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.ExportPolicyRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultExportPolicyResolutionServiceTest {

    @Mock ExportPolicyRepository exportPolicyRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupMembershipRepository membershipRepository;

    private DefaultExportPolicyResolutionService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultExportPolicyResolutionService(exportPolicyRepository, userRepository,
                membershipRepository);
    }

    @Test
    void returnsEmptyAndSkipsLookupsWhenNoEnabledPolicies() {
        stubPolicies();

        var result = service.resolveApplicable(orgId, datasourceId, userId);

        assertThat(result).isEmpty();
        verify(exportPolicyRepository)
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(orgId, datasourceId);
        verifyNoInteractions(userRepository, membershipRepository);
    }

    @Test
    void emptyScopeAppliesToEveryoneAndMapsView() {
        var policy = policy(ExportPolicyMode.DENY_CLASSIFIED);
        policy.setRowCap(null);
        policy.setDenyClassifications(new String[]{"PII", "GDPR"});
        stubPolicies(policy);
        stubUser(UserRoleType.ANALYST);
        stubGroupIds();

        var result = service.resolveApplicable(orgId, datasourceId, userId);

        assertThat(result).hasSize(1);
        var view = result.getFirst();
        assertThat(view.id()).isEqualTo(policy.getId());
        assertThat(view.datasourceId()).isEqualTo(datasourceId);
        assertThat(view.mode()).isEqualTo(ExportPolicyMode.DENY_CLASSIFIED);
        assertThat(view.rowCap()).isNull();
        assertThat(view.denyClassifications())
                .containsExactly(DataClassification.PII, DataClassification.GDPR);
        assertThat(view.appliesToRoles()).isEmpty();
        assertThat(view.appliesToGroupIds()).isEmpty();
        assertThat(view.appliesToUserIds()).isEmpty();
        assertThat(view.enabled()).isTrue();
        assertThat(view.createdAt()).isEqualTo(policy.getCreatedAt());
        assertThat(view.updatedAt()).isEqualTo(policy.getUpdatedAt());
    }

    @Test
    void emptyScopeAppliesToAdminWithoutImplicitBypass() {
        stubPolicies(policy(ExportPolicyMode.ROW_CAP));
        stubUser(UserRoleType.ADMIN);
        stubGroupIds();

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).hasSize(1);
    }

    @Test
    void roleMatchIsCaseInsensitiveAndTrimmed() {
        var policy = policy(ExportPolicyMode.WATERMARK);
        policy.setAppliesToRoles(new String[]{" Analyst "});
        stubPolicies(policy);
        stubUser(UserRoleType.ANALYST);
        stubGroupIds();

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).hasSize(1);
    }

    @Test
    void roleMismatchExcludesPolicy() {
        var policy = policy(ExportPolicyMode.WATERMARK);
        policy.setAppliesToRoles(new String[]{null, "ADMIN"});
        stubPolicies(policy);
        stubUser(UserRoleType.ANALYST);
        stubGroupIds();

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).isEmpty();
    }

    @Test
    void userScopeAppliesWhenUserMatches() {
        var policy = policy(ExportPolicyMode.ROW_CAP);
        policy.setAppliesToUserIds(new UUID[]{UUID.randomUUID(), userId});
        stubPolicies(policy);
        stubUser(UserRoleType.READONLY);
        stubGroupIds();

        var result = service.resolveApplicable(orgId, datasourceId, userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().appliesToUserIds()).contains(userId);
    }

    @Test
    void groupScopeAppliesWhenGroupMatches() {
        var groupId = UUID.randomUUID();
        var policy = policy(ExportPolicyMode.ROW_CAP);
        policy.setAppliesToGroupIds(new UUID[]{groupId});
        stubPolicies(policy);
        stubUser(UserRoleType.READONLY);
        stubGroupIds(groupId);

        var result = service.resolveApplicable(orgId, datasourceId, userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().appliesToGroupIds()).containsExactly(groupId);
    }

    @Test
    void scopedPolicyWithNoMatchingTargetIsExcluded() {
        var policy = policy(ExportPolicyMode.ROW_CAP);
        policy.setAppliesToRoles(new String[]{"ADMIN"});
        policy.setAppliesToUserIds(new UUID[]{UUID.randomUUID()});
        policy.setAppliesToGroupIds(new UUID[]{UUID.randomUUID()});
        stubPolicies(policy);
        stubUser(UserRoleType.ANALYST);
        stubGroupIds(UUID.randomUUID());

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).isEmpty();
    }

    @Test
    void groupScopeSkippedWhenUserHasNoGroups() {
        var policy = policy(ExportPolicyMode.ROW_CAP);
        policy.setAppliesToGroupIds(new UUID[]{UUID.randomUUID()});
        stubPolicies(policy);
        stubUser(UserRoleType.ANALYST);
        stubGroupIds();

        assertThat(service.resolveApplicable(orgId, datasourceId, userId)).isEmpty();
    }

    @Test
    void missingUserMatchesUserAndGroupScopesButNotRoleScopes() {
        var groupId = UUID.randomUUID();
        var rolePolicy = policy(ExportPolicyMode.WATERMARK);
        rolePolicy.setAppliesToRoles(new String[]{"ANALYST"});
        var userPolicy = policy(ExportPolicyMode.ROW_CAP);
        userPolicy.setAppliesToUserIds(new UUID[]{userId});
        var groupPolicy = policy(ExportPolicyMode.DENY_CLASSIFIED);
        groupPolicy.setAppliesToGroupIds(new UUID[]{groupId});
        stubPolicies(rolePolicy, userPolicy, groupPolicy);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        stubGroupIds(groupId);

        var result = service.resolveApplicable(orgId, datasourceId, userId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting("id")
                .containsExactly(userPolicy.getId(), groupPolicy.getId());
    }

    private void stubPolicies(ExportPolicyEntity... policies) {
        when(exportPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(orgId, datasourceId))
                .thenReturn(List.of(policies));
    }

    private void stubUser(UserRoleType role) {
        var user = new UserEntity();
        user.setId(userId);
        user.setRole(role);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private void stubGroupIds(UUID... groupIds) {
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupIds));
    }

    private ExportPolicyEntity policy(ExportPolicyMode mode) {
        var entity = new ExportPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setMode(mode);
        entity.setRowCap(mode == ExportPolicyMode.ROW_CAP ? 100 : null);
        entity.setEnabled(true);
        return entity;
    }
}
