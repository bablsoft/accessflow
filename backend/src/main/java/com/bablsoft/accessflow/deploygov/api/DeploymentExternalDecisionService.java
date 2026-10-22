package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.DecisionType;

import java.util.UUID;

/**
 * Applies a decision driven by an external ticketing system (ServiceNow / Jira, #992) to a
 * deployment still in review — the deployment twin of the workflow module's query-side
 * {@code ExternalDecisionService}, which delegates here. There is no reviewer: the transition is
 * system-attributed (no {@code deployment_review_decisions} row, null audit actor,
 * {@code trigger = ticket}) and the provenance rides on the published decided event.
 */
public interface DeploymentExternalDecisionService {

    /**
     * Transitions a {@code PENDING_REVIEW} deployment to {@code APPROVED} or {@code REJECTED} based
     * on an external ticket resolution. Idempotent and race-safe: returns {@code false} (without
     * throwing) when the deployment does not exist, belongs to another organization, or is no longer
     * in {@code PENDING_REVIEW} — a manual decision may have raced the webhook.
     *
     * @throws IllegalArgumentException {@code decision} is {@code REQUESTED_CHANGES}
     */
    boolean applyTicketDecision(UUID deploymentRequestId, UUID organizationId,
                                DecisionType decision, DeploymentTicketProvenance provenance);
}
