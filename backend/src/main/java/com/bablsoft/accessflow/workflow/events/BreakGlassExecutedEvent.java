package com.bablsoft.accessflow.workflow.events;

import java.util.UUID;

/**
 * Published after a break-glass query executes (AF-385). Drives the instant fanout to all org
 * admins (notifications + PagerDuty) and is the signal that a mandatory retro-review now exists.
 * Consumed asynchronously so a notification failure never affects the already-committed execution.
 */
public record BreakGlassExecutedEvent(
        UUID eventId,
        UUID queryRequestId,
        UUID organizationId) {
}
