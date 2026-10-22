package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * Where an external ticket decision on a deployment came from (#992). {@code ticketSystem} is the
 * channel type name ({@code SERVICENOW} / {@code JIRA}); {@code actor} is the free-text identity
 * the ticketing system reported (nullable — AccessFlow cannot map it to a user); {@code reason} is
 * the human-readable provenance, e.g. {@code "Jira ticket OPS-12 moved to 'Done' by jdoe"}.
 */
public record DeploymentTicketProvenance(
        String ticketSystem,
        String externalKey,
        UUID channelId,
        String actor,
        String reason) {
}
