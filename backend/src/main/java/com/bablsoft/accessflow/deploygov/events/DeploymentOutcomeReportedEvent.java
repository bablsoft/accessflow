package com.bablsoft.accessflow.deploygov.events;

import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;

import java.util.UUID;

/**
 * Published inside the outcome-recording transaction (#693) when a pipeline first reports what
 * happened after execution. The notifications fan-out (decision webhooks, #695) hangs off this;
 * idempotent repeats of the same outcome do not republish.
 */
public record DeploymentOutcomeReportedEvent(
        UUID organizationId,
        UUID deploymentRequestId,
        UUID pipelineId,
        DeploymentOutcome outcome,
        String detail) {
}
