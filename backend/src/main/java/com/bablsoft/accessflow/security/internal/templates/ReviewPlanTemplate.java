package com.bablsoft.accessflow.security.internal.templates;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.List;

public record ReviewPlanTemplate(
        String key,
        String name,
        String description,
        Defaults defaults
) {
    public record Defaults(
            boolean requiresAiReview,
            boolean requiresHumanApproval,
            int minApprovalsRequired,
            int approvalTimeoutHours,
            boolean autoApproveReads,
            List<ApproverDefault> approvers
    ) {
    }

    public record ApproverDefault(UserRoleType role, int stage) {
    }
}
