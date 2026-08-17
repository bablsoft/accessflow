package com.bablsoft.accessflow.core.api;

import java.util.List;

public record UpdateReviewPlanCommand(
        String name,
        String description,
        Boolean requiresAiReview,
        Boolean requiresHumanApproval,
        Integer minApprovalsRequired,
        Integer approvalTimeoutHours,
        Integer escalationAfterHours,
        Integer nudgeIntervalHours,
        Boolean autoApproveReads,
        List<String> notifyChannels,
        List<ReviewPlanView.ApproverRule> approvers
) {

    /** Convenience constructor for callers that predate escalation and nudges (#622). */
    public UpdateReviewPlanCommand(String name, String description, Boolean requiresAiReview,
                                   Boolean requiresHumanApproval, Integer minApprovalsRequired,
                                   Integer approvalTimeoutHours, Boolean autoApproveReads,
                                   List<String> notifyChannels,
                                   List<ReviewPlanView.ApproverRule> approvers) {
        this(name, description, requiresAiReview, requiresHumanApproval, minApprovalsRequired,
                approvalTimeoutHours, null, null, autoApproveReads, notifyChannels, approvers);
    }
}
