package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * Approval-outcome prediction (issue AF-645): trains a per-organization logistic model over the
 * org's historical <em>human</em> review decisions and scores queries that land in
 * {@code PENDING_REVIEW} with the probability a reviewer will approve them.
 *
 * <p><strong>Advisory only.</strong> The probability is a triage signal shown to reviewers. It is
 * never an input to the routing engine, grant coverage, break-glass, or any other decision path —
 * only the read side (query detail / review queue) consumes it.
 *
 * <p><strong>Every method is fail-safe.</strong> None of them propagates an exception to its caller:
 * the serving methods persist a {@code failed=true} sentinel row and return, and the training
 * methods log and move on. A prediction failure must never affect workflow state.
 */
public interface ApprovalPredictionService {

    /**
     * Scores {@code queryRequestId} and persists exactly one {@code approval_predictions} row —
     * either a probability, or a {@code skipped} row whose reason says why (the feature is off, or
     * the org has no serving model yet). Idempotent: a row already present is left alone.
     */
    void predictForQuery(UUID queryRequestId);

    /**
     * Re-scores {@code queryRequestId} when its pre-flight cost estimate (AF-624) arrived after the
     * query had already been scored without one. A no-op unless the persisted prediction recorded a
     * missing estimate, the estimate is now usable, and the query is still awaiting review. This is
     * the only path that replaces an existing prediction.
     */
    void refreshForLateEstimate(UUID queryRequestId);

    /**
     * Retrains every organization's model. One organization failing does not stop the others.
     * Driven by the scheduled retrain job.
     */
    void trainAll();

    /**
     * Retrains one organization's model and refreshes its row with the resulting quality metrics.
     * The model is marked serving only when the sample-count and holdout-AUC gates both pass; below
     * either gate the row is still written (with {@code serving=false}) so an admin can see why.
     */
    void trainForOrganization(UUID organizationId);
}
