package com.partqam.accessflow.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewPlanView(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        boolean requiresAiReview,
        boolean requiresHumanApproval,
        int minApprovalsRequired,
        int approvalTimeoutHours,
        boolean autoApproveReads,
        List<String> notifyChannels,
        List<ApproverRule> approvers,
        Instant createdAt
) {
    public record ApproverRule(UUID userId, UserRoleType role, int stage) {
    }
}
