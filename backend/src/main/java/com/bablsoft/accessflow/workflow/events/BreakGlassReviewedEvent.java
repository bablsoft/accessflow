package com.bablsoft.accessflow.workflow.events;

import java.util.UUID;

/**
 * Published when an admin acknowledges (reconciles) a break-glass retro-review (AF-385).
 */
public record BreakGlassReviewedEvent(
        UUID eventId,
        UUID queryRequestId,
        UUID organizationId,
        UUID reviewedByUserId) {
}
