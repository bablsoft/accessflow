package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence over {@code query_tickets} (AF-453) — tickets auto-created in an external ticketing
 * system (ServiceNow / Jira) for workflow events on a query. Lives in core so both the
 * notifications module (which creates tickets and applies inbound status updates) and the workflow
 * module (which embeds linked tickets in the query detail response) can reach it without a module
 * cycle.
 */
public interface QueryTicketService {

    /** Records a freshly created ticket. */
    QueryTicketView recordCreated(RecordTicketCommand command);

    /** Create-once dedupe check: has this channel already opened a ticket for this query+event? */
    boolean existsFor(UUID channelId, UUID queryRequestId, String triggerEvent);

    /**
     * Applies an inbound status update to the ticket identified by {@code (channelId, externalId)}.
     * Returns the updated view, or empty when no such ticket is linked (unknown tickets are
     * ignored, not an error — the external system may notify about unrelated records).
     */
    Optional<QueryTicketView> updateStatus(UUID channelId, String externalId, String status,
                                           String resolution);

    /** All tickets linked to a query, oldest first, scoped to the caller's organization. */
    List<QueryTicketView> listByQueryRequest(UUID queryRequestId, UUID organizationId);
}
