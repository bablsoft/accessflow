package com.bablsoft.accessflow.deploygov.api;

import java.util.List;
import java.util.UUID;

/**
 * Create a deployment environment. {@code sortOrder} is the environment's position in the
 * pipeline's promotion ladder — unique per pipeline; null appends after the current last one
 * (#877). {@code requireReview} defaults to {@code true} and {@code allowBreakGlass} to
 * {@code false} when null; {@code requiredApprovals} and {@code reviewPlanId} are nullable
 * per-environment overrides of the pipeline's plan. {@code tags} are free-form labels (null or
 * empty = no tags); the service trims, drops blanks and de-duplicates. {@code datasourceId}
 * optionally names the datasource the environment's schema changes land on — it must belong to
 * the pipeline's organization; null = deploy-only.
 */
public record CreateDeploymentEnvironmentCommand(
        String name,
        Integer sortOrder,
        Boolean requireReview,
        Integer requiredApprovals,
        UUID reviewPlanId,
        Boolean allowBreakGlass,
        List<String> tags,
        UUID datasourceId) {
}
