package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Command to record a ticket freshly created in an external ticketing system (AF-453).
 * {@code status} is the external system's initial state label (e.g. {@code New} / {@code To Do}).
 */
public record RecordTicketCommand(
        UUID organizationId,
        UUID queryRequestId,
        UUID channelId,
        String ticketSystem,
        String triggerEvent,
        String externalId,
        String externalKey,
        String url,
        String status) {
}
