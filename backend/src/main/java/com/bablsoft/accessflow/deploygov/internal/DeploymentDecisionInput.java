package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything {@link DeploymentDecisionEvaluator} needs about one deployment, real or hypothetical
 * (issue AF-967).
 *
 * <p>An explicit record rather than the request entity: the live path builds it from a persisted row
 * and the simulator builds it from lookup views, and neither should have to fake the other's shape.
 *
 * @param at the instant routing's time-window conditions are evaluated at. The live path passes
 *           {@code clock.instant()}; the simulator may pass a hypothetical one, which is the whole
 *           reason a maintenance-hour policy can be checked without waiting for the maintenance hour
 * @param environmentRequiredApprovals the environment's own override, or {@code null} to fall back to
 *                                     the resolved review plan's minimum
 */
record DeploymentDecisionInput(UUID organizationId, UUID pipelineId, PipelineProvider provider,
                               String environmentName, boolean environmentRequiresReview,
                               Integer environmentRequiredApprovals, UUID reviewPlanId,
                               String version, AiOutcome aiOutcome, RiskLevel riskLevel,
                               Instant at) {
}
