package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserGroupView;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentGroupPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentGroupPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineGroupPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineUserPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineGroupPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentPermissionServiceTest {

    @Mock
    private DeploymentPipelineRepository pipelineRepository;

    @Mock
    private DeploymentPipelineUserPermissionRepository permissionRepository;

    @Mock
    private DeploymentPipelineGroupPermissionRepository groupPermissionRepository;

    @Mock
    private EffectiveDeploymentPermissionResolver permissionResolver;

    @Mock
    private UserQueryService userQueryService;

    @Mock
    private UserGroupService userGroupService;

    @InjectMocks
    private DefaultDeploymentPermissionService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @Test
    void listPermissionsDenormalizesUserIdentity() {
        stubPipeline();
        var permission = userPermission();
        when(permissionRepository.findByPipelineId(pipelineId)).thenReturn(List.of(permission));
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(orgId)));

        var views = service.listPermissions(pipelineId, orgId);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).userEmail()).isEqualTo("dev@acme.test");
        assertThat(views.get(0).userDisplayName()).isEqualTo("Dev");
    }

    @Test
    void listPermissionsRejectsCrossOrgPipeline() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listPermissions(pipelineId, orgId))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void grantPermissionCreatesFreshGrantWithProvenance() {
        stubPipeline();
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(orgId)));
        when(permissionRepository.findByPipelineIdAndUserId(pipelineId, userId))
                .thenReturn(Optional.empty());
        when(permissionRepository.save(any(DeploymentPipelineUserPermissionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.grantPermission(pipelineId, orgId, adminId,
                new GrantDeploymentPermissionCommand(userId, true, false, null));

        assertThat(view.canTrigger()).isTrue();
        assertThat(view.canBreakGlass()).isFalse();
        verify(permissionRepository).save(org.mockito.ArgumentMatchers.argThat(
                e -> adminId.equals(e.getCreatedBy()) && pipelineId.equals(e.getPipelineId())));
    }

    @Test
    void grantPermissionUpsertsExistingGrant() {
        stubPipeline();
        var existing = userPermission();
        var originalCreatedBy = existing.getCreatedBy();
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(orgId)));
        when(permissionRepository.findByPipelineIdAndUserId(pipelineId, userId))
                .thenReturn(Optional.of(existing));
        when(permissionRepository.save(any(DeploymentPipelineUserPermissionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var expiry = Instant.now().plusSeconds(3600);
        var view = service.grantPermission(pipelineId, orgId, adminId,
                new GrantDeploymentPermissionCommand(userId, true, true, expiry));

        assertThat(view.id()).isEqualTo(existing.getId());
        assertThat(view.expiresAt()).isEqualTo(expiry);
        assertThat(existing.getCreatedBy()).isEqualTo(originalCreatedBy);
    }

    @Test
    void grantPermissionRejectsUserFromAnotherOrg() {
        stubPipeline();
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(UUID.randomUUID())));

        assertThatThrownBy(() -> service.grantPermission(pipelineId, orgId, adminId,
                new GrantDeploymentPermissionCommand(userId, true, false, null)))
                .isInstanceOf(UserNotFoundException.class);
        verify(permissionRepository, never()).save(any());
    }

    @Test
    void updatePermissionMutatesFieldsAndPreservesProvenance() {
        stubPipeline();
        var existing = userPermission();
        var originalCreatedBy = existing.getCreatedBy();
        when(permissionRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(permissionRepository.save(any(DeploymentPipelineUserPermissionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(orgId)));

        var view = service.updatePermission(pipelineId, orgId, existing.getId(),
                new UpdateDeploymentPermissionCommand(false, true, null));

        assertThat(view.canTrigger()).isFalse();
        assertThat(view.canBreakGlass()).isTrue();
        assertThat(existing.getCreatedBy()).isEqualTo(originalCreatedBy);
    }

    @Test
    void updatePermissionRejectsForeignPermission() {
        stubPipeline();
        var foreign = userPermission();
        foreign.setPipelineId(UUID.randomUUID());
        when(permissionRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.updatePermission(pipelineId, orgId, foreign.getId(),
                new UpdateDeploymentPermissionCommand(true, false, null)))
                .isInstanceOf(DeploymentPermissionNotFoundException.class);
    }

    @Test
    void updatePermissionRejectsMissingPermission() {
        stubPipeline();
        var permissionId = UUID.randomUUID();
        when(permissionRepository.findById(permissionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePermission(pipelineId, orgId, permissionId,
                new UpdateDeploymentPermissionCommand(true, false, null)))
                .isInstanceOf(DeploymentPermissionNotFoundException.class);
    }

    @Test
    void revokePermissionDeletesOwnedGrant() {
        stubPipeline();
        var existing = userPermission();
        when(permissionRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.revokePermission(pipelineId, orgId, existing.getId());

        verify(permissionRepository).delete(existing);
    }

    @Test
    void revokePermissionRejectsForeignPermission() {
        stubPipeline();
        var foreign = userPermission();
        foreign.setPipelineId(UUID.randomUUID());
        when(permissionRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.revokePermission(pipelineId, orgId, foreign.getId()))
                .isInstanceOf(DeploymentPermissionNotFoundException.class);
        verify(permissionRepository, never())
                .delete(any(DeploymentPipelineUserPermissionEntity.class));
    }

    @Test
    void listGroupPermissionsDenormalizesGroup() {
        stubPipeline();
        var permission = groupPermission();
        when(groupPermissionRepository.findByPipelineId(pipelineId)).thenReturn(List.of(permission));
        when(userGroupService.getGroup(groupId, orgId)).thenReturn(group());

        var views = service.listGroupPermissions(pipelineId, orgId);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).groupName()).isEqualTo("platform");
        assertThat(views.get(0).memberCount()).isEqualTo(4L);
    }

    @Test
    void grantGroupPermissionUpsertsAndReturnsView() {
        stubPipeline();
        when(userGroupService.getGroup(groupId, orgId)).thenReturn(group());
        when(groupPermissionRepository.findByPipelineIdAndGroupId(pipelineId, groupId))
                .thenReturn(Optional.empty());
        when(groupPermissionRepository.save(any(DeploymentPipelineGroupPermissionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.grantGroupPermission(pipelineId, orgId, adminId,
                new GrantDeploymentGroupPermissionCommand(groupId, true, true, null));

        assertThat(view.groupId()).isEqualTo(groupId);
        assertThat(view.canBreakGlass()).isTrue();
        verify(groupPermissionRepository).save(org.mockito.ArgumentMatchers.argThat(
                e -> orgId.equals(e.getOrganizationId()) && adminId.equals(e.getCreatedBy())));
    }

    @Test
    void updateGroupPermissionMutatesFieldsAndPreservesProvenance() {
        stubPipeline();
        var existing = groupPermission();
        var originalCreatedBy = existing.getCreatedBy();
        when(groupPermissionRepository.findById(existing.getId()))
                .thenReturn(Optional.of(existing));
        when(groupPermissionRepository.save(any(DeploymentPipelineGroupPermissionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(userGroupService.getGroup(groupId, orgId)).thenReturn(group());

        var view = service.updateGroupPermission(pipelineId, orgId, existing.getId(),
                new UpdateDeploymentGroupPermissionCommand(false, false, null));

        assertThat(view.canTrigger()).isFalse();
        assertThat(existing.getCreatedBy()).isEqualTo(originalCreatedBy);
    }

    @Test
    void updateGroupPermissionRejectsForeignPermission() {
        stubPipeline();
        var foreign = groupPermission();
        foreign.setPipelineId(UUID.randomUUID());
        when(groupPermissionRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.updateGroupPermission(pipelineId, orgId, foreign.getId(),
                new UpdateDeploymentGroupPermissionCommand(true, false, null)))
                .isInstanceOf(DeploymentPermissionNotFoundException.class);
    }

    @Test
    void revokeGroupPermissionRejectsForeignPermission() {
        stubPipeline();
        var foreign = groupPermission();
        foreign.setPipelineId(UUID.randomUUID());
        when(groupPermissionRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.revokeGroupPermission(pipelineId, orgId, foreign.getId()))
                .isInstanceOf(DeploymentPermissionNotFoundException.class);
        verify(groupPermissionRepository, never())
                .delete(any(DeploymentPipelineGroupPermissionEntity.class));
    }

    @Test
    void revokeGroupPermissionDeletesOwnedGrant() {
        stubPipeline();
        var existing = groupPermission();
        when(groupPermissionRepository.findById(existing.getId()))
                .thenReturn(Optional.of(existing));

        service.revokeGroupPermission(pipelineId, orgId, existing.getId());

        verify(groupPermissionRepository).delete(existing);
    }

    @Test
    void effectivePermissionDelegatesToResolver() {
        var effective = new EffectiveDeploymentPermission(pipelineId, userId, true, false, null);
        when(permissionResolver.resolve(pipelineId, userId)).thenReturn(Optional.of(effective));

        assertThat(service.effectivePermission(pipelineId, userId)).contains(effective);
    }

    private void stubPipeline() {
        var pipeline = new DeploymentPipelineEntity();
        pipeline.setId(pipelineId);
        pipeline.setOrganizationId(orgId);
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline));
    }

    private DeploymentPipelineUserPermissionEntity userPermission() {
        var e = new DeploymentPipelineUserPermissionEntity();
        e.setId(UUID.randomUUID());
        e.setPipelineId(pipelineId);
        e.setUserId(userId);
        e.setCanTrigger(true);
        e.setCreatedBy(UUID.randomUUID());
        return e;
    }

    private DeploymentPipelineGroupPermissionEntity groupPermission() {
        var e = new DeploymentPipelineGroupPermissionEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setPipelineId(pipelineId);
        e.setGroupId(groupId);
        e.setCanTrigger(true);
        e.setCreatedBy(UUID.randomUUID());
        return e;
    }

    private UserView user(UUID organizationId) {
        return new UserView(userId, "dev@acme.test", "Dev", UserRoleType.ANALYST, null, null,
                organizationId, true, null, null, null, null, false, false, Instant.now(), null,
                Instant.now());
    }

    private UserGroupView group() {
        return new UserGroupView(groupId, orgId, "platform", null, 4L, Instant.now(),
                Instant.now());
    }
}
