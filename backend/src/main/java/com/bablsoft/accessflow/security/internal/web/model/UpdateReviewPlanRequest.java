package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateReviewPlanRequest(
        @Size(min = 1, max = 255, message = "{validation.review_plan_name.max}") String name,
        @Size(max = 2000, message = "{validation.review_plan_description.max}") String description,
        Boolean requiresAiReview,
        Boolean requiresHumanApproval,
        @Min(value = 1, message = "{validation.review_plan_min_approvals.range}")
        @Max(value = 10, message = "{validation.review_plan_min_approvals.range}")
        Integer minApprovalsRequired,
        @Min(value = 1, message = "{validation.review_plan_timeout.range}")
        @Max(value = 8760, message = "{validation.review_plan_timeout.range}")
        Integer approvalTimeoutHours,
        Boolean autoApproveReads,
        List<String> notifyChannels,
        @Valid List<ReviewPlanApproverDto> approvers
) {
}
