package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a ticket linked to a query request (AF-453). {@code ticketSystem} is the channel
 * type name ({@code SERVICENOW} / {@code JIRA}) and {@code triggerEvent} the notification event
 * name that created the ticket — both plain strings because the enums live in the notifications
 * module, which core cannot depend on.
 */
public record QueryTicketView(
        UUID id,
        UUID organizationId,
        UUID queryRequestId,
        UUID channelId,
        String ticketSystem,
        String triggerEvent,
        String externalId,
        String externalKey,
        String url,
        String status,
        String resolution,
        Instant createdAt,
        Instant updatedAt) {
}
