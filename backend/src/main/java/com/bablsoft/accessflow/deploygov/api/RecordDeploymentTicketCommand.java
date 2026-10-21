package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Command to record a change ticket freshly created in an external ticketing system for a
 * deployment request (#990). {@code status} is the external system's initial state label.
 */
public record RecordDeploymentTicketCommand(
        UUID organizationId,
        UUID deploymentRequestId,
        UUID channelId,
        String ticketSystem,
        String triggerEvent,
        String externalId,
        String externalKey,
        String url,
        String status) {
}
