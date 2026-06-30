package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.requestgroups.api.GroupReviewService.PendingGroupReview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record PendingGroupReviewResponse(
        UUID requestGroupId, String name, UUID submittedByUserId, String submittedByDisplayName,
        int memberCount, RiskLevel aiRiskLevel, Integer aiRiskScore, int currentStage,
        int requiredApprovals, Instant createdAt) {

    static PendingGroupReviewResponse from(PendingGroupReview p) {
        return new PendingGroupReviewResponse(p.requestGroupId(), p.name(), p.submittedByUserId(),
                p.submittedByDisplayName(), p.memberCount(), p.aiRiskLevel(), p.aiRiskScore(),
                p.currentStage(), p.requiredApprovals(), p.createdAt());
    }

    record Page(List<PendingGroupReviewResponse> content, int page, int size, long totalElements,
                int totalPages) {
        static Page from(PageResponse<PendingGroupReview> page) {
            return new Page(page.content().stream().map(PendingGroupReviewResponse::from).toList(),
                    page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }
}
