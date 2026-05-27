package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.internal.templates.ReviewPlanTemplate;

import java.util.List;

public record ReviewPlanTemplateResponse(
        String key,
        String name,
        String description,
        TemplateDefaults defaults
) {
    public record TemplateDefaults(
            boolean requiresAiReview,
            boolean requiresHumanApproval,
            int minApprovalsRequired,
            int approvalTimeoutHours,
            boolean autoApproveReads,
            List<TemplateApprover> approvers
    ) {
    }

    public record TemplateApprover(UserRoleType role, int stage) {
    }

    public static ReviewPlanTemplateResponse from(ReviewPlanTemplate template) {
        var defaults = template.defaults();
        var approvers = defaults.approvers().stream()
                .map(a -> new TemplateApprover(a.role(), a.stage()))
                .toList();
        return new ReviewPlanTemplateResponse(
                template.key(),
                template.name(),
                template.description(),
                new TemplateDefaults(
                        defaults.requiresAiReview(),
                        defaults.requiresHumanApproval(),
                        defaults.minApprovalsRequired(),
                        defaults.approvalTimeoutHours(),
                        defaults.autoApproveReads(),
                        approvers
                )
        );
    }
}
