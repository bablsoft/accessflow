package com.bablsoft.accessflow.deploygov.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence over {@code deployment_tickets} (#990) — change tickets opened in an external
 * ticketing system (ServiceNow / Jira) for a deployment request. The deploygov sibling of
 * {@code core.api.QueryTicketService}: the notifications module creates tickets and applies inbound
 * status updates through it, and the gate reads the persisted {@code decision}.
 */
public interface DeploymentTicketService {

    /** Records a freshly created ticket. */
    DeploymentTicketView recordCreated(RecordDeploymentTicketCommand command);

    /** Create-once dedupe check: has this channel already opened a ticket for this deployment? */
    boolean existsFor(UUID channelId, UUID deploymentRequestId);

    /**
     * Applies an inbound status update to the ticket identified by {@code (channelId, externalId)}.
     * {@code decision} ({@code APPROVED} / {@code REJECTED} / null) is the caller's mapping of the
     * status onto the channel's approve/reject lists and replaces the stored one, except that an
     * {@code APPROVED} decision is sticky: a null decision leaves it (and {@code decidedAt}) in
     * place, so only a {@code REJECTED} mapping overrides an approval (#993). {@code decidedAt} is
     * stamped when the decision changes to a non-null value, kept when the same decision is
     * re-sent, and cleared when a null decision clears a {@code REJECTED} one. Returns the updated view, or
     * empty when no such ticket is linked (unknown tickets are ignored, not an error).
     */
    Optional<DeploymentTicketView> updateStatus(UUID channelId, String externalId, String status,
                                                String resolution, String decision);

    /** All tickets linked to a deployment request, oldest first, scoped to the organization. */
    List<DeploymentTicketView> listByDeploymentRequest(UUID deploymentRequestId,
                                                       UUID organizationId);
}
