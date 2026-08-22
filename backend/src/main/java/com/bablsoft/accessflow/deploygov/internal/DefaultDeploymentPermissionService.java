package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserGroupView;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.deploygov.api.DeploymentGroupPermissionView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentGroupPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentGroupPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineGroupPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineUserPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineGroupPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultDeploymentPermissionService implements DeploymentPermissionService {

    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentPipelineUserPermissionRepository permissionRepository;
    private final DeploymentPipelineGroupPermissionRepository groupPermissionRepository;
    private final EffectiveDeploymentPermissionResolver permissionResolver;
    private final UserQueryService userQueryService;
    private final UserGroupService userGroupService;

    @Override
    @Transactional(readOnly = true)
    public List<DeploymentPermissionView> listPermissions(UUID pipelineId, UUID organizationId) {
        require(pipelineId, organizationId);
        return permissionRepository.findByPipelineId(pipelineId).stream()
                .map(this::toPermissionView)
                .toList();
    }

    @Override
    @Transactional
    public DeploymentPermissionView grantPermission(UUID pipelineId, UUID organizationId,
                                                    UUID grantedByUserId,
                                                    GrantDeploymentPermissionCommand command) {
        require(pipelineId, organizationId);
        var target = userQueryService.findById(command.userId())
                .filter(u -> organizationId.equals(u.organizationId()))
                .orElseThrow(() -> new UserNotFoundException(command.userId()));
        var entity = permissionRepository.findByPipelineIdAndUserId(pipelineId, command.userId())
                .orElseGet(() -> {
                    var fresh = new DeploymentPipelineUserPermissionEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setPipelineId(pipelineId);
                    fresh.setUserId(command.userId());
                    fresh.setCreatedBy(grantedByUserId);
                    return fresh;
                });
        entity.setCanTrigger(command.canTrigger());
        entity.setCanBreakGlass(command.canBreakGlass());
        entity.setExpiresAt(command.expiresAt());
        return toPermissionView(permissionRepository.save(entity), target);
    }

    @Override
    @Transactional
    public DeploymentPermissionView updatePermission(UUID pipelineId, UUID organizationId,
                                                     UUID permissionId,
                                                     UpdateDeploymentPermissionCommand command) {
        require(pipelineId, organizationId);
        var entity = permissionRepository.findById(permissionId)
                .filter(p -> p.getPipelineId().equals(pipelineId))
                .orElseThrow(() -> new DeploymentPermissionNotFoundException(permissionId));
        entity.setCanTrigger(command.canTrigger());
        entity.setCanBreakGlass(command.canBreakGlass());
        entity.setExpiresAt(command.expiresAt());
        return toPermissionView(permissionRepository.save(entity));
    }

    @Override
    @Transactional
    public void revokePermission(UUID pipelineId, UUID organizationId, UUID permissionId) {
        require(pipelineId, organizationId);
        var entity = permissionRepository.findById(permissionId)
                .filter(p -> p.getPipelineId().equals(pipelineId))
                .orElseThrow(() -> new DeploymentPermissionNotFoundException(permissionId));
        permissionRepository.delete(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeploymentGroupPermissionView> listGroupPermissions(UUID pipelineId, UUID organizationId) {
        require(pipelineId, organizationId);
        return groupPermissionRepository.findByPipelineId(pipelineId).stream()
                .map(this::toGroupPermissionView)
                .toList();
    }

    @Override
    @Transactional
    public DeploymentGroupPermissionView grantGroupPermission(UUID pipelineId, UUID organizationId,
                                                              UUID grantedByUserId,
                                                              GrantDeploymentGroupPermissionCommand command) {
        require(pipelineId, organizationId);
        // Validates the group exists in this organization (throws UserGroupNotFoundException → 404).
        var group = userGroupService.getGroup(command.groupId(), organizationId);
        var entity = groupPermissionRepository.findByPipelineIdAndGroupId(pipelineId, command.groupId())
                .orElseGet(() -> {
                    var fresh = new DeploymentPipelineGroupPermissionEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setOrganizationId(organizationId);
                    fresh.setPipelineId(pipelineId);
                    fresh.setGroupId(command.groupId());
                    fresh.setCreatedBy(grantedByUserId);
                    return fresh;
                });
        entity.setCanTrigger(command.canTrigger());
        entity.setCanBreakGlass(command.canBreakGlass());
        entity.setExpiresAt(command.expiresAt());
        return toGroupPermissionView(groupPermissionRepository.save(entity), group);
    }

    @Override
    @Transactional
    public DeploymentGroupPermissionView updateGroupPermission(UUID pipelineId, UUID organizationId,
                                                               UUID permissionId,
                                                               UpdateDeploymentGroupPermissionCommand command) {
        require(pipelineId, organizationId);
        var entity = groupPermissionRepository.findById(permissionId)
                .filter(p -> p.getPipelineId().equals(pipelineId))
                .orElseThrow(() -> new DeploymentPermissionNotFoundException(permissionId));
        entity.setCanTrigger(command.canTrigger());
        entity.setCanBreakGlass(command.canBreakGlass());
        entity.setExpiresAt(command.expiresAt());
        return toGroupPermissionView(groupPermissionRepository.save(entity));
    }

    @Override
    @Transactional
    public void revokeGroupPermission(UUID pipelineId, UUID organizationId, UUID permissionId) {
        require(pipelineId, organizationId);
        var entity = groupPermissionRepository.findById(permissionId)
                .filter(p -> p.getPipelineId().equals(pipelineId))
                .orElseThrow(() -> new DeploymentPermissionNotFoundException(permissionId));
        groupPermissionRepository.delete(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EffectiveDeploymentPermission> effectivePermission(UUID pipelineId, UUID userId) {
        return permissionResolver.resolve(pipelineId, userId);
    }

    private void require(UUID pipelineId, UUID organizationId) {
        pipelineRepository.findByIdAndOrganizationId(pipelineId, organizationId)
                .orElseThrow(() -> new DeploymentPipelineNotFoundException(pipelineId));
    }

    private DeploymentPermissionView toPermissionView(DeploymentPipelineUserPermissionEntity e) {
        var user = userQueryService.findById(e.getUserId()).orElse(null);
        return toPermissionView(e, user);
    }

    private static DeploymentPermissionView toPermissionView(DeploymentPipelineUserPermissionEntity e,
                                                             UserView user) {
        return new DeploymentPermissionView(
                e.getId(), e.getPipelineId(), e.getUserId(),
                user != null ? user.email() : null,
                user != null ? user.displayName() : null,
                e.isCanTrigger(), e.isCanBreakGlass(), e.getExpiresAt(), e.getCreatedAt());
    }

    private DeploymentGroupPermissionView toGroupPermissionView(DeploymentPipelineGroupPermissionEntity e) {
        var group = userGroupService.getGroup(e.getGroupId(), e.getOrganizationId());
        return toGroupPermissionView(e, group);
    }

    private static DeploymentGroupPermissionView toGroupPermissionView(
            DeploymentPipelineGroupPermissionEntity e, UserGroupView group) {
        return new DeploymentGroupPermissionView(
                e.getId(), e.getPipelineId(), e.getGroupId(),
                group != null ? group.name() : null,
                group != null ? group.memberCount() : 0L,
                e.isCanTrigger(), e.isCanBreakGlass(), e.getExpiresAt(), e.getCreatedAt());
    }
}
