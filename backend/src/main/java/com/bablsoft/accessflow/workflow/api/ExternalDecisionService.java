package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Applies a decision driven by an external ticketing system (ServiceNow / Jira, AF-453) to a query
 * still in review. Unlike {@link ReviewService} there is no reviewer: the transition is
 * system-attributed (no {@code review_decisions} row, mirroring the review-timeout path) and the
 * provenance is carried in {@code reason} on the published auto-decision event.
 */
public interface ExternalDecisionService {

    /**
     * Transitions a {@code PENDING_REVIEW} query to {@code APPROVED} or {@code REJECTED} based on
     * an external ticket resolution. Idempotent and race-safe: returns {@code false} (without
     * throwing) when the query does not exist, belongs to another organization, or is no longer in
     * {@code PENDING_REVIEW} — a manual decision may have raced the webhook.
     *
     * @param reason human-readable provenance, e.g.
     *        {@code "ServiceNow ticket INC0010023 resolved by jdoe"}; recorded in the audit trail
     */
    boolean applyTicketDecision(UUID queryRequestId, UUID organizationId,
                                ExternalTicketDecision decision, String reason);
}
