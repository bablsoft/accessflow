package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.ReviewPlanView;
import com.bablsoft.accessflow.core.api.UserRoleType;
import jakarta.validation.constraints.Min;

import java.util.UUID;

public record ReviewPlanApproverDto(
        UUID userId,
        UserRoleType role,
        @Min(value = 1, message = "{validation.review_plan_approver_stage.min}") int stage
) {
    public static ReviewPlanApproverDto from(ReviewPlanView.ApproverRule rule) {
        return new ReviewPlanApproverDto(rule.userId(), rule.role(), rule.stage());
    }

    public ReviewPlanView.ApproverRule toRule() {
        return new ReviewPlanView.ApproverRule(userId, role, stage);
    }
}
