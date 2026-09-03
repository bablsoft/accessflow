package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Reviewer-facing approve/reject of governed deployment requests (#692). Single review stage;
 * while the request is still {@code PENDING_REVIEW}, decisions are idempotent per
 * {@code (request, reviewer, stage)} — a repeat returns the original decision with
 * {@code duplicate=true}; once the request left review, any decision is a state-guard 409. The
 * submitter — the API key's owning user — can never decide their own deployment.
 */
public interface DeploymentReviewService {

    PageResponse<PendingDeploymentReview> listPending(ReviewerContext context,
                                                      PendingDeploymentReviewFilter filter,
                                                      PageRequest pageRequest);

    DecisionOutcome approve(UUID deploymentRequestId, ReviewerContext context, String comment);

    DecisionOutcome reject(UUID deploymentRequestId, ReviewerContext context, String comment);

    /**
     * Whether the caller may record a <em>new</em> decision on this request right now — the
     * predicate {@code approve}/{@code reject} enforce, answered without throwing so a read path
     * can publish it, and additionally {@code false} once the caller has already voted at this
     * stage (their decision is idempotent, so a second one would silently replay the first). An
     * unknown or cross-organization id answers {@code false} rather than raising, so the flag can
     * never turn a detail read into an existence oracle.
     */
    boolean canReview(UUID deploymentRequestId, ReviewerContext context);

    record ReviewerContext(UUID userId, UUID organizationId, String roleName,
                           Set<Permission> permissions) {
    }

    /** Optional narrowing of the pending-review queue. Nullable. */
    record PendingDeploymentReviewFilter(UUID pipelineId) {
    }

    record PendingDeploymentReview(
            UUID deploymentRequestId, UUID pipelineId, String pipelineName, UUID environmentId,
            String environmentName, UUID submittedByUserId, String version, String commitSha,
            String runUrl, String justification, UUID aiAnalysisId, RiskLevel aiRiskLevel,
            Integer aiRiskScore, String aiSummary, int currentStage, int requiredApprovals,
            Instant scheduledFor, Instant createdAt) {
    }

    record DecisionOutcome(UUID decisionId, DecisionType decision, QueryStatus resultingStatus,
                           boolean duplicate) {
    }
}
