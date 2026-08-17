package com.bablsoft.accessflow.core.api;

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
        /** Hours before an idle request is escalated; null disables escalation (#622). */
        Integer escalationAfterHours,
        /** Hours between reminders to reviewers who have not decided; null disables nudges. */
        Integer nudgeIntervalHours,
        boolean autoApproveReads,
        List<String> notifyChannels,
        List<ApproverRule> approvers,
        Instant createdAt
) {

    /** Convenience constructor for callers that predate escalation and nudges. */
    public ReviewPlanView(UUID id, UUID organizationId, String name, String description,
                          boolean requiresAiReview, boolean requiresHumanApproval,
                          int minApprovalsRequired, int approvalTimeoutHours,
                          boolean autoApproveReads, List<String> notifyChannels,
                          List<ApproverRule> approvers, Instant createdAt) {
        this(id, organizationId, name, description, requiresAiReview, requiresHumanApproval,
                minApprovalsRequired, approvalTimeoutHours, null, null, autoApproveReads,
                notifyChannels, approvers, createdAt);
    }
    /** {@code role} is a role NAME (system or custom), matched case-insensitively (AF-522). */
    public record ApproverRule(UUID userId, String role, int stage) {
    }
}
