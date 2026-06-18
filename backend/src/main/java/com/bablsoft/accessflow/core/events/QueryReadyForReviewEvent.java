package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a query has transitioned from {@code PENDING_AI} to {@code PENDING_REVIEW}
 * (either the AI completed successfully and human review is required, or the AI failed). The
 * audit and notifications modules are the consumers.
 *
 * <p>When the transition was forced by a routing policy ({@code REQUIRE_APPROVALS} / {@code
 * ESCALATE}, AF-446) the matched-policy fields are populated so the audit log records the matched
 * policy and the resolved minimum approvals; they are {@code null} for the plan-based and AI-failed
 * paths. Carries primitives only — {@code core.events} cannot import {@code workflow.api}.
 */
public record QueryReadyForReviewEvent(
        UUID queryRequestId,
        UUID matchedPolicyId,
        String routingReason,
        Integer effectiveMinApprovals) {

    /** Plain transition with no routing policy match (plan-based or AI-failed path). */
    public QueryReadyForReviewEvent(UUID queryRequestId) {
        this(queryRequestId, null, null, null);
    }
}
