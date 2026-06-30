package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorMaskingPolicyEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorMaskingPolicyRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultApiConnectorMaskingResolutionServiceTest {

    @Mock ApiConnectorMaskingPolicyRepository policyRepository;
    @Mock UserQueryService userQueryService;
    @Mock UserGroupService userGroupService;

    private DefaultApiConnectorMaskingResolutionService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiConnectorMaskingResolutionService(policyRepository, userQueryService,
                userGroupService, new ObjectMapper());
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(UserRoleType.ANALYST)));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of());
    }

    private UserView user(UserRoleType role) {
        return new UserView(userId, "u@x.io", "U", role, orgId, true, AuthProviderType.LOCAL, null,
                null, "en", false, Instant.now());
    }

    private ApiConnectorMaskingPolicyEntity policy() {
        var e = new ApiConnectorMaskingPolicyEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setConnectorId(connectorId);
        e.setMatcherType(ApiMaskingMatcherType.JSON_PATH);
        e.setFieldRef("user.ssn");
        e.setStrategy(MaskingStrategy.PARTIAL);
        e.setStrategyParams("{\"visible_suffix\":\"4\"}");
        return e;
    }

    @Test
    void returnsEmptyWhenNoPolicies() {
        when(policyRepository.findAllByOrganizationIdAndConnectorIdAndEnabledTrue(orgId, connectorId))
                .thenReturn(List.of());

        assertThat(service.resolveApplicable(orgId, connectorId, userId)).isEmpty();
    }

    @Test
    void resolvesPolicyWithParsedParamsWhenNotRevealed() {
        when(policyRepository.findAllByOrganizationIdAndConnectorIdAndEnabledTrue(orgId, connectorId))
                .thenReturn(List.of(policy()));

        var resolved = service.resolveApplicable(orgId, connectorId, userId);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().fieldRef()).isEqualTo("user.ssn");
        assertThat(resolved.getFirst().strategy()).isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(resolved.getFirst().params()).containsEntry("visible_suffix", "4");
    }

    @Test
    void skipsPolicyWhenRoleRevealed() {
        var p = policy();
        p.setRevealToRoles(new String[]{"ANALYST"});
        when(policyRepository.findAllByOrganizationIdAndConnectorIdAndEnabledTrue(orgId, connectorId))
                .thenReturn(List.of(p));

        assertThat(service.resolveApplicable(orgId, connectorId, userId)).isEmpty();
    }

    @Test
    void skipsPolicyWhenUserRevealed() {
        var p = policy();
        p.setRevealToUserIds(new UUID[]{userId});
        when(policyRepository.findAllByOrganizationIdAndConnectorIdAndEnabledTrue(orgId, connectorId))
                .thenReturn(List.of(p));

        assertThat(service.resolveApplicable(orgId, connectorId, userId)).isEmpty();
    }

    @Test
    void skipsPolicyWhenGroupRevealed() {
        var groupId = UUID.randomUUID();
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        var p = policy();
        p.setRevealToGroupIds(new UUID[]{groupId});
        when(policyRepository.findAllByOrganizationIdAndConnectorIdAndEnabledTrue(orgId, connectorId))
                .thenReturn(List.of(p));

        assertThat(service.resolveApplicable(orgId, connectorId, userId)).isEmpty();
    }

    @Test
    void appliesPolicyWhenRevealRoleDiffers() {
        var p = policy();
        p.setRevealToRoles(new String[]{"ADMIN"});
        when(policyRepository.findAllByOrganizationIdAndConnectorIdAndEnabledTrue(orgId, connectorId))
                .thenReturn(List.of(p));

        assertThat(service.resolveApplicable(orgId, connectorId, userId)).hasSize(1);
    }
}
