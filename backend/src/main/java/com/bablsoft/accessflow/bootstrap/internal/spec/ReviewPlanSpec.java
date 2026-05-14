package com.bablsoft.accessflow.bootstrap.internal.spec;

import java.util.List;

public record ReviewPlanSpec(
        String name,
        String description,
        Boolean requiresAiReview,
        Boolean requiresHumanApproval,
        Integer minApprovalsRequired,
        Integer approvalTimeoutHours,
        Boolean autoApproveReads,
        List<String> notifyChannelNames,
        List<String> approverEmails
) {

    public ReviewPlanSpec {
        notifyChannelNames = notifyChannelNames == null ? List.of() : List.copyOf(notifyChannelNames);
        approverEmails = approverEmails == null ? List.of() : List.copyOf(approverEmails);
    }
}
