package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.ReviewPlanView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewPlanResponse(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        boolean requiresAiReview,
        boolean requiresHumanApproval,
        int minApprovalsRequired,
        int approvalTimeoutHours,
        boolean autoApproveReads,
        List<String> notifyChannels,
        List<ReviewPlanApproverDto> approvers,
        Instant createdAt
) {
    public static ReviewPlanResponse from(ReviewPlanView view) {
        return new ReviewPlanResponse(
                view.id(),
                view.organizationId(),
                view.name(),
                view.description(),
                view.requiresAiReview(),
                view.requiresHumanApproval(),
                view.minApprovalsRequired(),
                view.approvalTimeoutHours(),
                view.autoApproveReads(),
                view.notifyChannels(),
                view.approvers().stream().map(ReviewPlanApproverDto::from).toList(),
                view.createdAt());
    }
}
