package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Update a deployment environment. Null fields are left unchanged. {@code clearRequiredApprovals}
 * / {@code clearReviewPlan} unset the nullable overrides and win over a value supplied in the
 * same command.
 */
public record UpdateDeploymentEnvironmentCommand(
        String name,
        Integer sortOrder,
        Boolean requireReview,
        Integer requiredApprovals,
        Boolean clearRequiredApprovals,
        UUID reviewPlanId,
        Boolean clearReviewPlan,
        Boolean allowBreakGlass) {
}
