package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateReviewPlanRequest(
        @NotBlank(message = "{validation.review_plan_name.required}")
        @Size(max = 255, message = "{validation.review_plan_name.max}") String name,
        @Size(max = 2000, message = "{validation.review_plan_description.max}") String description,
        Boolean requiresAiReview,
        Boolean requiresHumanApproval,
        @Min(value = 1, message = "{validation.review_plan_min_approvals.range}")
        @Max(value = 10, message = "{validation.review_plan_min_approvals.range}")
        Integer minApprovalsRequired,
        @Min(value = 1, message = "{validation.review_plan_timeout.range}")
        @Max(value = 8760, message = "{validation.review_plan_timeout.range}")
        Integer approvalTimeoutHours,
        /** Hours before an idle request is escalated (#622). Null/absent disables escalation. */
        @Min(value = 1, message = "{validation.review_plan_escalation.range}")
        @Max(value = 8760, message = "{validation.review_plan_escalation.range}")
        Integer escalationAfterHours,
        /** Hours between reminders to undecided reviewers. Null/absent disables nudges. */
        @Min(value = 1, message = "{validation.review_plan_nudge.range}")
        @Max(value = 8760, message = "{validation.review_plan_nudge.range}")
        Integer nudgeIntervalHours,
        Boolean autoApproveReads,
        List<String> notifyChannels,
        @Valid List<ReviewPlanApproverDto> approvers
) {
}
