package com.bablsoft.accessflow.requestgroups.api;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Reviewer-facing approve/reject of grouped requests. One decision per reviewer/stage covers the
 * whole group; the eligible approvers are the union across all member plans, and the group advances
 * to {@code APPROVED} only when every member plan's per-stage threshold is satisfied. The submitter
 * can never approve their own group.
 */
public interface GroupReviewService {

    PageResponse<PendingGroupReview> listPending(ReviewerContext context, PageRequest pageRequest);

    DecisionOutcome approve(UUID requestGroupId, ReviewerContext context, String comment);

    DecisionOutcome reject(UUID requestGroupId, ReviewerContext context, String comment);

    record ReviewerContext(UUID userId, UUID organizationId, String roleName,
                           Set<Permission> permissions) {
    }

    record PendingGroupReview(
            UUID requestGroupId, String name, UUID submittedByUserId, String submittedByDisplayName,
            int memberCount, RiskLevel aiRiskLevel, Integer aiRiskScore, int currentStage,
            int requiredApprovals, Instant createdAt) {
    }

    record DecisionOutcome(UUID decisionId, DecisionType decision, RequestGroupStatus resultingStatus,
                           boolean wasIdempotentReplay) {
    }
}
