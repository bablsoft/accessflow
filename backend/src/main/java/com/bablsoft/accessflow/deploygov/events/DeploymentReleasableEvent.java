package com.bablsoft.accessflow.deploygov.events;

import java.util.UUID;

/**
 * Published inside {@code markReleasable}'s transaction (#693) when
 * {@code ScheduledDeploymentReleaseJob} observes an {@code APPROVED} request become releasable —
 * a {@code HOLD} freeze window closing or a {@code scheduled_for} instant passing. One-shot per
 * request ({@code deployment_requests.release_notified_at}); the notifications fan-out (#695)
 * turns it into push-style webhook delivery so pipelines need not tight-poll the gate.
 */
public record DeploymentReleasableEvent(
        UUID organizationId,
        UUID deploymentRequestId,
        UUID pipelineId,
        UUID environmentId,
        String version) {
}
