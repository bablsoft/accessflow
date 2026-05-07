package com.partqam.accessflow.core.api;

import java.util.List;
import java.util.UUID;

public record CreateReviewPlanCommand(
        UUID organizationId,
        String name,
        String description,
        Boolean requiresAiReview,
        Boolean requiresHumanApproval,
        Integer minApprovalsRequired,
        Integer approvalTimeoutHours,
        Boolean autoApproveReads,
        List<String> notifyChannels,
        List<ReviewPlanView.ApproverRule> approvers
) {
}
