package com.bablsoft.accessflow.deploygov.events;

import java.util.UUID;

/**
 * Published by {@code DeploymentRequestStateService} (#985) when a {@code PENDING_REVIEW}
 * deployment request is escalated past its resolved review plan's {@code escalation_after_hours}. Notify-only: it never touches eligibility or the decision path.
 */
public record DeploymentReviewEscalatedEvent(UUID deploymentRequestId) {
}
