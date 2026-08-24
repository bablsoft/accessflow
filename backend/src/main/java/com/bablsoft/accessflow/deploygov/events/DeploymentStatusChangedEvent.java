package com.bablsoft.accessflow.deploygov.events;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Published from the single status-transition chokepoint on every change, so notifications (#695)
 * and audit fan-out can hang off one event rather than every call site.
 */
public record DeploymentStatusChangedEvent(
        UUID deploymentRequestId, UUID submitterId, QueryStatus oldStatus, QueryStatus newStatus) {
}
