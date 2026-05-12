package com.bablsoft.accessflow.core.api;

import java.util.List;

public record UpdateReviewPlanCommand(
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
