package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read view of a deployment environment (a pipeline's promotion stage). {@code sortOrder} is
 * unique within the pipeline; {@code datasourceId} is the datasource its schema changes land on,
 * or null for a deploy-only environment (#877). {@code changeTicketRequired} marks an environment
 * whose deployments must carry an approved external change ticket (#990). {@code verification} is
 * the post-deployment verification config, or null when verification is off (#1023) — or when the
 * stored config cannot be read. Null here is therefore display-only: a verification consumer must
 * decode the stored blob itself and treat a parse failure as a failed verification, never as off.
 */
public record DeploymentEnvironmentView(
        UUID id,
        UUID pipelineId,
        String name,
        int sortOrder,
        boolean requireReview,
        Integer requiredApprovals,
        UUID reviewPlanId,
        boolean allowBreakGlass,
        Instant createdAt,
        List<String> tags,
        UUID datasourceId,
        boolean changeTicketRequired,
        DeploymentVerificationConfig verification) {
}
