package com.bablsoft.accessflow.deploygov.events;

import java.util.UUID;

/**
 * Published synchronously after a break-glass deployment request has been force-approved (#692).
 * The workflow module listens in the same transaction to open the mandatory retro-review
 * ({@code break_glass_events}) — event-based so deploygov does not depend on workflow, keeping the
 * module graph acyclic (the same AF-567 shape as the apigov sibling).
 */
public record DeploymentBreakGlassExecutedEvent(
        UUID organizationId,
        UUID deploymentRequestId,
        UUID pipelineId,
        UUID submitterUserId,
        String justification) {
}
