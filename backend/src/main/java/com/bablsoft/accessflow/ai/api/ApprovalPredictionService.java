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
 * <p><strong>Fail-safety, precisely.</strong> A prediction failure must never affect workflow state,
 * and the implementations are driven by asynchronous, commit-scoped event listeners, so a failure
 * structurally cannot reach the transition that triggered it. What each method guarantees to its own
 * caller differs, and is documented per method below rather than blanket-promised here.
 */
public interface ApprovalPredictionService {

    /**
     * Scores {@code queryRequestId} and persists one {@code approval_predictions} row — a
     * probability, a {@code skipped} row whose reason says why (the feature is off, or the org has no
     * serving model yet), or a {@code failed} sentinel. No row is written only when the query request
     * is no longer readable.
     *
     * <p>Writes once per query: a row already present is left as it is, except by
     * {@link #refreshForLateEstimate}. A scoring failure is absorbed into the sentinel row; a failure
     * in the guard lookups that precede scoring propagates (the listener absorbs it).
     */
    void predictForQuery(UUID queryRequestId);

    /**
     * Re-scores {@code queryRequestId} when its pre-flight cost estimate (AF-624) arrived after the
     * query had already been scored without one. A no-op unless the persisted prediction recorded a
     * missing estimate, the estimate is now usable, and the query is still awaiting review. This is
     * the only path that replaces an existing prediction, and it replaces only with a real
     * probability — never with a sentinel, since the row it overwrites already holds a number a
     * reviewer may have acted on.
     */
    void refreshForLateEstimate(UUID queryRequestId);

    /**
     * Retrains every organization's model. One organization failing is logged and does not stop the
     * others. Driven by the scheduled retrain job.
     *
     * <p>Not absolutely non-throwing: only the per-organization training sits inside the guarded
     * block, so a failure while paging the organization list still propagates. The scheduled job
     * catches that — do not read this method as a reason to drop the job's own {@code catch}.
     */
    void trainAll();

    /**
     * Retrains one organization's model and refreshes its row with the resulting quality metrics.
     * The model is marked serving only when the sample-count and holdout-AUC gates both pass; below
     * either gate the row is still written (with {@code serving=false}) so an admin can see why.
     *
     * <p>Unlike {@link #trainAll}, this propagates a training failure, so a caller retraining one
     * named organization can react to it.
     */
    void trainForOrganization(UUID organizationId);
}
