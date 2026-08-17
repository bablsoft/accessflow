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
        /** True to set escalation_after_hours back to null (off); null alone means "unchanged". */
        boolean clearEscalationAfterHours,
        /** True to set nudge_interval_hours back to null (off). */
        boolean clearNudgeIntervalHours,
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
                approvalTimeoutHours, null, null, false, false, autoApproveReads, notifyChannels,
                approvers);
    }
}
