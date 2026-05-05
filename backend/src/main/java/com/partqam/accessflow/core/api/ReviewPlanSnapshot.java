package com.partqam.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Cross-module DTO carrying the policy fields of a review plan plus its sorted approver rules.
 * {@code maxStage} is the highest {@code stage} value across approvers, used by the workflow
 * service to detect "is this the last stage" without enumerating rules.
 */
public record ReviewPlanSnapshot(
        UUID id,
        UUID organizationId,
        boolean requiresAiReview,
        boolean requiresHumanApproval,
        int minApprovalsRequired,
        boolean autoApproveReads,
        int maxStage,
        List<ApproverRule> approvers) {
}
