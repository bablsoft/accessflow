package com.bablsoft.accessflow.core.api;

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
        Integer escalationAfterHours,
        Integer nudgeIntervalHours,
        Boolean autoApproveReads,
        List<String> notifyChannels,
        List<ReviewPlanView.ApproverRule> approvers
) {

    /** Convenience constructor for callers that predate escalation and nudges (#622). */
    public CreateReviewPlanCommand(UUID organizationId, String name, String description,
                                   Boolean requiresAiReview, Boolean requiresHumanApproval,
                                   Integer minApprovalsRequired, Integer approvalTimeoutHours,
                                   Boolean autoApproveReads, List<String> notifyChannels,
                                   List<ReviewPlanView.ApproverRule> approvers) {
        this(organizationId, name, description, requiresAiReview, requiresHumanApproval,
                minApprovalsRequired, approvalTimeoutHours, null, null, autoApproveReads,
                notifyChannels, approvers);
    }
}
