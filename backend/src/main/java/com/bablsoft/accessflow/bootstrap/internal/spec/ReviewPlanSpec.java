package com.bablsoft.accessflow.bootstrap.internal.spec;

import java.util.List;

public record ReviewPlanSpec(
        String name,
        String description,
        Boolean requiresAiReview,
        Boolean requiresHumanApproval,
        Integer minApprovalsRequired,
        Integer approvalTimeoutHours,
        Integer escalationAfterHours,
        Integer nudgeIntervalHours,
        Boolean autoApproveReads,
        List<String> notifyChannelNames,
        List<String> approverEmails
) {

    public ReviewPlanSpec {
        notifyChannelNames = notifyChannelNames == null ? List.of() : List.copyOf(notifyChannelNames);
        approverEmails = approverEmails == null ? List.of() : List.copyOf(approverEmails);
    }

    /** Convenience constructor for specs that predate escalation and nudges (#622). */
    public ReviewPlanSpec(String name, String description, Boolean requiresAiReview,
                          Boolean requiresHumanApproval, Integer minApprovalsRequired,
                          Integer approvalTimeoutHours, Boolean autoApproveReads,
                          List<String> notifyChannelNames, List<String> approverEmails) {
        this(name, description, requiresAiReview, requiresHumanApproval, minApprovalsRequired,
                approvalTimeoutHours, null, null, autoApproveReads, notifyChannelNames,
                approverEmails);
    }
}
