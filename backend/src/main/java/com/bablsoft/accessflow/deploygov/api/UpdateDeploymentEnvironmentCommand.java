package com.bablsoft.accessflow.deploygov.api;

import java.util.List;
import java.util.UUID;

/**
 * Update a deployment environment. Null fields are left unchanged. {@code clearRequiredApprovals}
 * / {@code clearReviewPlan} / {@code clearDatasource} unset the nullable fields and win over a
 * value supplied in the same command. {@code tags} replaces the whole tag list — an explicit
 * empty list clears it, so no {@code clearTags} flag exists. A {@code sortOrder} already held by
 * another environment of the pipeline is rejected (#877).
 */
public record UpdateDeploymentEnvironmentCommand(
        String name,
        Integer sortOrder,
        Boolean requireReview,
        Integer requiredApprovals,
        Boolean clearRequiredApprovals,
        UUID reviewPlanId,
        Boolean clearReviewPlan,
        Boolean allowBreakGlass,
        List<String> tags,
        UUID datasourceId,
        Boolean clearDatasource) {
}
