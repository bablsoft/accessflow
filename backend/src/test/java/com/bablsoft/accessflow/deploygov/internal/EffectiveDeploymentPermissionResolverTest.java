package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineGroupPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineUserPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineGroupPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EffectiveDeploymentPermissionResolverTest {

    @Mock
    private DeploymentPipelineUserPermissionRepository userPermissionRepository;

    @Mock
    private DeploymentPipelineGroupPermissionRepository groupPermissionRepository;

    @Mock
    private UserGroupService userGroupService;

    @InjectMocks
    private EffectiveDeploymentPermissionResolver resolver;

    private final UUID pipelineId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @Test
    void resolveUnionsFlagsAcrossDirectAndGroupGrants() {
        var direct = userPermission(true, false, null);
        var group = groupPermission(false, true, null);
        when(userPermissionRepository.findByPipelineIdAndUserId(pipelineId, userId))
                .thenReturn(Optional.of(direct));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId))).thenReturn(List.of(group));

        var resolved = resolver.resolve(pipelineId, userId).orElseThrow();

        assertThat(resolved.canTrigger()).isTrue();
        assertThat(resolved.canBreakGlass()).isTrue();
        assertThat(resolved.expiresAt()).isNull();
    }

    @Test
    void resolveGroupOnlyGrant() {
        when(userPermissionRepository.findByPipelineIdAndUserId(pipelineId, userId))
                .thenReturn(Optional.empty());
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId)))
                .thenReturn(List.of(groupPermission(true, false, null)));

        var resolved = resolver.resolve(pipelineId, userId).orElseThrow();

        assertThat(resolved.canTrigger()).isTrue();
        assertThat(resolved.canBreakGlass()).isFalse();
    }

    @Test
    void resolveIgnoresExpiredDirectGrant() {
        when(userPermissionRepository.findByPipelineIdAndUserId(pipelineId, userId))
                .thenReturn(Optional.of(userPermission(true, true, Instant.now().minusSeconds(60))));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of());

        assertThat(resolver.resolve(pipelineId, userId)).isEmpty();
    }

    @Test
    void resolveIgnoresExpiredGroupGrant() {
        when(userPermissionRepository.findByPipelineIdAndUserId(pipelineId, userId))
                .thenReturn(Optional.of(userPermission(true, false, null)));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId)))
                .thenReturn(List.of(groupPermission(false, true, Instant.now().minusSeconds(60))));

        var resolved = resolver.resolve(pipelineId, userId).orElseThrow();

        assertThat(resolved.canBreakGlass()).isFalse();
    }

    @Test
    void resolveIgnoresGroupGrantOnAnotherPipeline() {
        var foreign = groupPermission(true, true, null);
        foreign.setPipelineId(UUID.randomUUID());
        when(userPermissionRepository.findByPipelineIdAndUserId(pipelineId, userId))
                .thenReturn(Optional.empty());
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId))).thenReturn(List.of(foreign));

        assertThat(resolver.resolve(pipelineId, userId)).isEmpty();
    }

    @Test
    void resolveNullExpiryWinsOverLaterExpiry() {
        var expiring = userPermission(true, false, Instant.now().plusSeconds(3600));
        var standing = groupPermission(false, false, null);
        when(userPermissionRepository.findByPipelineIdAndUserId(pipelineId, userId))
                .thenReturn(Optional.of(expiring));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId))).thenReturn(List.of(standing));

        var resolved = resolver.resolve(pipelineId, userId).orElseThrow();

        assertThat(resolved.expiresAt()).isNull();
    }

    @Test
    void resolveKeepsLatestExpiryWhenEveryGrantExpires() {
        var sooner = Instant.now().plusSeconds(600);
        var later = Instant.now().plusSeconds(7200);
        when(userPermissionRepository.findByPipelineIdAndUserId(pipelineId, userId))
                .thenReturn(Optional.of(userPermission(true, false, sooner)));
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findByGroupIdIn(List.of(groupId)))
                .thenReturn(List.of(groupPermission(false, false, later)));

        var resolved = resolver.resolve(pipelineId, userId).orElseThrow();

        assertThat(resolved.expiresAt()).isEqualTo(later);
    }

    @Test
    void resolveEmptyWhenNoGrant() {
        when(userPermissionRepository.findByPipelineIdAndUserId(pipelineId, userId))
                .thenReturn(Optional.empty());
        when(userGroupService.findGroupIdsForUser(userId)).thenReturn(List.of());

        assertThat(resolver.resolve(pipelineId, userId)).isEmpty();
    }

    private DeploymentPipelineUserPermissionEntity userPermission(boolean canTrigger,
                                                                  boolean canBreakGlass,
                                                                  Instant expiresAt) {
        var e = new DeploymentPipelineUserPermissionEntity();
        e.setId(UUID.randomUUID());
        e.setPipelineId(pipelineId);
        e.setUserId(userId);
        e.setCanTrigger(canTrigger);
        e.setCanBreakGlass(canBreakGlass);
        e.setExpiresAt(expiresAt);
        return e;
    }

    private DeploymentPipelineGroupPermissionEntity groupPermission(boolean canTrigger,
                                                                    boolean canBreakGlass,
                                                                    Instant expiresAt) {
        var e = new DeploymentPipelineGroupPermissionEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(UUID.randomUUID());
        e.setPipelineId(pipelineId);
        e.setGroupId(groupId);
        e.setCanTrigger(canTrigger);
        e.setCanBreakGlass(canBreakGlass);
        e.setExpiresAt(expiresAt);
        return e;
    }
}
