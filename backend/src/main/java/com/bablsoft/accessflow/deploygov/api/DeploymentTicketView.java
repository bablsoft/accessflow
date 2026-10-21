package com.bablsoft.accessflow.deploygov.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a change ticket linked to a deployment request (#990). {@code ticketSystem} is
 * the channel type name ({@code SERVICENOW} / {@code JIRA}), {@code triggerEvent} the notification
 * event that opened the ticket and {@code decision} {@code APPROVED} / {@code REJECTED} / null —
 * plain strings because the enums live in the notifications module.
 */
public record DeploymentTicketView(
        UUID id,
        UUID organizationId,
        UUID deploymentRequestId,
        UUID channelId,
        String ticketSystem,
        String triggerEvent,
        String externalId,
        String externalKey,
        String url,
        String status,
        String resolution,
        String decision,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt) {
}
