package com.bablsoft.accessflow.deploygov.events;

import java.util.UUID;

/**
 * Published inside the state-service transaction (#1025) when a post-deployment verification
 * breaches and the request flips {@code EXECUTED → FAILED}. The deployment's {@code outcome} stays
 * {@code SUCCEEDED}; no rollback happens. The notification fan-out hangs off this (#1026).
 */
public record DeploymentVerificationFailedEvent(
        UUID organizationId,
        UUID deploymentRequestId,
        UUID pipelineId,
        String detail) {
}
