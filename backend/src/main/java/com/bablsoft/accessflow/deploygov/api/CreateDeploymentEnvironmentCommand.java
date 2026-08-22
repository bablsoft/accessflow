package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Create a deployment environment. {@code sortOrder} defaults to 0, {@code requireReview} to
 * {@code true} and {@code allowBreakGlass} to {@code false} when null; {@code requiredApprovals}
 * and {@code reviewPlanId} are nullable per-environment overrides of the pipeline's plan.
 */
public record CreateDeploymentEnvironmentCommand(
        String name,
        Integer sortOrder,
        Boolean requireReview,
        Integer requiredApprovals,
        UUID reviewPlanId,
        Boolean allowBreakGlass) {
}
