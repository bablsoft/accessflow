package com.bablsoft.accessflow.deploygov.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-user and per-group trigger grants on a deployment pipeline, plus the merged
 * effective-permission lookup the trigger and gate services build on (#688, epic #682).
 */
public interface DeploymentPermissionService {

    List<DeploymentPermissionView> listPermissions(UUID pipelineId, UUID organizationId);

    /** Upserts by (pipeline, user); the target user must belong to the organization. */
    DeploymentPermissionView grantPermission(UUID pipelineId, UUID organizationId, UUID grantedByUserId,
                                             GrantDeploymentPermissionCommand command);

    DeploymentPermissionView updatePermission(UUID pipelineId, UUID organizationId, UUID permissionId,
                                              UpdateDeploymentPermissionCommand command);

    void revokePermission(UUID pipelineId, UUID organizationId, UUID permissionId);

    List<DeploymentGroupPermissionView> listGroupPermissions(UUID pipelineId, UUID organizationId);

    /** Upserts by (pipeline, group); the target group must belong to the organization. */
    DeploymentGroupPermissionView grantGroupPermission(UUID pipelineId, UUID organizationId,
                                                       UUID grantedByUserId,
                                                       GrantDeploymentGroupPermissionCommand command);

    DeploymentGroupPermissionView updateGroupPermission(UUID pipelineId, UUID organizationId,
                                                        UUID permissionId,
                                                        UpdateDeploymentGroupPermissionCommand command);

    void revokeGroupPermission(UUID pipelineId, UUID organizationId, UUID permissionId);

    /**
     * The user's merged effective permission on the pipeline — the most-permissive union of the
     * direct grant and every unexpired group grant — or empty when no unexpired grant applies.
     */
    Optional<EffectiveDeploymentPermission> effectivePermission(UUID pipelineId, UUID userId);
}
