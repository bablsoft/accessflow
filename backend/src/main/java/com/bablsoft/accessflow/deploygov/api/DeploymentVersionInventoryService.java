package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only version inventory over the #741 tracking projection (#742): which version runs where
 * right now, per-environment deployment history, and the read-time drift indicator. Drift is
 * computed at read time only — no scheduled job, no notifications, no semver parsing.
 *
 * <p>Visibility on the per-pipeline reads is {@code DEPLOYMENT_PIPELINE_MANAGE},
 * {@code DEPLOYMENT_REVIEW}, {@code QUERY_ADMIN}, or an effective {@code can_trigger} grant on
 * the pipeline; anything else — including a cross-org pipeline — reads as
 * {@link DeploymentPipelineNotFoundException}, never a 403, so the endpoints are not an
 * existence oracle.
 */
public interface DeploymentVersionInventoryService {

    /**
     * The version matrix for one pipeline: every environment (never-deployed ones included, with
     * null version fields), ordered by {@code sortOrder} then name.
     *
     * @throws DeploymentPipelineNotFoundException unknown, cross-org, or not visible to the caller
     */
    List<DeploymentEnvironmentVersionView> pipelineMatrix(UUID pipelineId, UUID organizationId,
                                                          UUID callerId,
                                                          Set<Permission> callerPermissions);

    /**
     * The org-wide matrix over environments deployed at least once, ordered by pipeline name,
     * then {@code sortOrder}, then environment name. The caller's functional permission is
     * enforced at the web layer; this read is plain org-scoped.
     */
    PageResponse<DeploymentEnvironmentVersionView> list(
            DeploymentEnvironmentVersionListFilter filter, PageRequest pageRequest);

    /**
     * The environment's deployment timeline, newest first; {@code status} null = all.
     *
     * @throws DeploymentPipelineNotFoundException    unknown, cross-org, or not visible pipeline
     * @throws DeploymentEnvironmentNotFoundException environment missing or on another pipeline
     */
    PageResponse<DeploymentVersionHistoryEntryView> history(UUID pipelineId, UUID environmentId,
                                                            QueryStatus status, UUID organizationId,
                                                            UUID callerId,
                                                            Set<Permission> callerPermissions,
                                                            PageRequest pageRequest);
}
