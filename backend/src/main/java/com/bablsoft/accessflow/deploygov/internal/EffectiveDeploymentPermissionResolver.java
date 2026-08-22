package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineGroupPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineUserPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineGroupPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The single point that unions a user's direct pipeline grant with every unexpired group grant
 * for a group they belong to — the deploygov mirror of the apigov
 * {@code EffectiveApiConnectorPermissionResolver} (AF-530), collapsed to the two flags a
 * deployment grant carries. The trigger and gate services (#691/#693) route through here so
 * every enforcement point sees the same most-permissive effective permission.
 */
@Service
@RequiredArgsConstructor
class EffectiveDeploymentPermissionResolver {

    private final DeploymentPipelineUserPermissionRepository userPermissionRepository;
    private final DeploymentPipelineGroupPermissionRepository groupPermissionRepository;
    private final UserGroupService userGroupService;

    /** Merged effective permission on a pipeline, or empty when no unexpired grant applies. */
    @Transactional(readOnly = true)
    Optional<EffectiveDeploymentPermission> resolve(UUID pipelineId, UUID userId) {
        var now = Instant.now();
        var contributions = new ArrayList<Contribution>();
        userPermissionRepository.findByPipelineIdAndUserId(pipelineId, userId)
                .filter(p -> isActive(p.getExpiresAt(), now))
                .map(Contribution::from)
                .ifPresent(contributions::add);
        var groupIds = userGroupService.findGroupIdsForUser(userId);
        if (!groupIds.isEmpty()) {
            groupPermissionRepository.findByGroupIdIn(groupIds).stream()
                    .filter(p -> p.getPipelineId().equals(pipelineId))
                    .filter(p -> isActive(p.getExpiresAt(), now))
                    .map(Contribution::from)
                    .forEach(contributions::add);
        }
        if (contributions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(merge(pipelineId, userId, contributions));
    }

    private static boolean isActive(Instant expiresAt, Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    private static EffectiveDeploymentPermission merge(UUID pipelineId, UUID userId,
                                                       List<Contribution> parts) {
        boolean canTrigger = false;
        boolean canBreakGlass = false;
        Instant expiresAt = parts.get(0).expiresAt();
        boolean anyNeverExpires = false;
        for (var p : parts) {
            canTrigger |= p.canTrigger();
            canBreakGlass |= p.canBreakGlass();
            if (p.expiresAt() == null) {
                anyNeverExpires = true;
            } else if (expiresAt != null && p.expiresAt().isAfter(expiresAt)) {
                expiresAt = p.expiresAt();
            }
        }
        return new EffectiveDeploymentPermission(pipelineId, userId, canTrigger, canBreakGlass,
                anyNeverExpires ? null : expiresAt);
    }

    private record Contribution(boolean canTrigger, boolean canBreakGlass, Instant expiresAt) {

        static Contribution from(DeploymentPipelineUserPermissionEntity e) {
            return new Contribution(e.isCanTrigger(), e.isCanBreakGlass(), e.getExpiresAt());
        }

        static Contribution from(DeploymentPipelineGroupPermissionEntity e) {
            return new Contribution(e.isCanTrigger(), e.isCanBreakGlass(), e.getExpiresAt());
        }
    }
}
