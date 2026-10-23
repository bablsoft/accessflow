package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Per-pipeline read visibility shared by the version inventory (#742) and the metrics (#984):
 * {@code DEPLOYMENT_PIPELINE_MANAGE}, {@code DEPLOYMENT_REVIEW}, {@code QUERY_ADMIN}, or an
 * effective {@code can_trigger} grant. Failure is 404, never 403, exactly like the gate: an
 * under-permissioned read must look like an unknown pipeline, so the endpoints are not an
 * existence oracle.
 */
@Component
@RequiredArgsConstructor
class DeploymentPipelineVisibility {

    private final DeploymentPipelineRepository pipelineRepository;
    private final EffectiveDeploymentPermissionResolver permissionResolver;

    DeploymentPipelineEntity requireVisible(UUID pipelineId, UUID organizationId, UUID callerId,
                                            Set<Permission> callerPermissions) {
        var pipeline = pipelineRepository.findByIdAndOrganizationId(pipelineId, organizationId)
                .orElseThrow(() -> new DeploymentPipelineNotFoundException(pipelineId));
        if (canViewAll(callerPermissions) || canTrigger(pipelineId, callerId)) {
            return pipeline;
        }
        throw new DeploymentPipelineNotFoundException(pipelineId);
    }

    private static boolean canViewAll(Set<Permission> callerPermissions) {
        return callerPermissions != null
                && (callerPermissions.contains(Permission.DEPLOYMENT_PIPELINE_MANAGE)
                        || callerPermissions.contains(Permission.DEPLOYMENT_REVIEW)
                        || callerPermissions.contains(Permission.QUERY_ADMIN));
    }

    private boolean canTrigger(UUID pipelineId, UUID callerId) {
        return permissionResolver.resolve(pipelineId, callerId)
                .map(EffectiveDeploymentPermission::canTrigger)
                .orElse(false);
    }
}
