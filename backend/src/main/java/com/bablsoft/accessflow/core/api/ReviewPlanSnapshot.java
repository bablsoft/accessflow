package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Cross-module DTO carrying the policy fields of a review plan plus its sorted approver rules.
 * {@code maxStage} is the highest {@code stage} value across approvers, used by the workflow
 * service to detect "is this the last stage" without enumerating rules.
 * {@code notifyChannelIds} are the {@code notification_channels.id} UUIDs the plan has opted
 * into; consumed by the notifications module to fan out review-related events.
 */
public record ReviewPlanSnapshot(
        UUID id,
        UUID organizationId,
        boolean requiresAiReview,
        boolean requiresHumanApproval,
        int minApprovalsRequired,
        boolean autoApproveReads,
        int maxStage,
        List<ApproverRule> approvers,
        List<UUID> notifyChannelIds,
        /** Hours before an idle request is escalated; null disables escalation (#622). */
        Integer escalationAfterHours,
        /** Hours between reminders to undecided reviewers; null disables nudges. */
        Integer nudgeIntervalHours) {

    /** Convenience constructor for callers that predate escalation and nudges. */
    public ReviewPlanSnapshot(UUID id, UUID organizationId, boolean requiresAiReview,
                              boolean requiresHumanApproval, int minApprovalsRequired,
                              boolean autoApproveReads, int maxStage,
                              List<ApproverRule> approvers, List<UUID> notifyChannelIds) {
        this(id, organizationId, requiresAiReview, requiresHumanApproval, minApprovalsRequired,
                autoApproveReads, maxStage, approvers, notifyChannelIds, null, null);
    }
}
