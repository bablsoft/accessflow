package com.bablsoft.accessflow.requestgroups.api;

import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full read model for a grouped request with its ordered members. */
public record RequestGroupView(
        UUID id,
        UUID organizationId,
        UUID submittedByUserId,
        String submittedByDisplayName,
        String name,
        String description,
        RequestGroupStatus status,
        boolean continueOnError,
        Instant scheduledFor,
        RiskLevel aiRiskLevel,
        Integer aiRiskScore,
        int requiredApprovals,
        int currentReviewStage,
        String errorMessage,
        Instant executionStartedAt,
        Instant executionCompletedAt,
        Instant createdAt,
        Instant updatedAt,
        List<RequestGroupItemView> items,
        /** The human an API-key submitter acted for (#874); null for a human submission. */
        UUID onBehalfOfUserId) {

    /** Backward-compatible constructor without the #874 on-behalf-of principal. */
    public RequestGroupView(UUID id, UUID organizationId, UUID submittedByUserId,
                            String submittedByDisplayName, String name, String description,
                            RequestGroupStatus status, boolean continueOnError, Instant scheduledFor,
                            RiskLevel aiRiskLevel, Integer aiRiskScore, int requiredApprovals,
                            int currentReviewStage, String errorMessage, Instant executionStartedAt,
                            Instant executionCompletedAt, Instant createdAt, Instant updatedAt,
                            List<RequestGroupItemView> items) {
        this(id, organizationId, submittedByUserId, submittedByDisplayName, name, description, status,
                continueOnError, scheduledFor, aiRiskLevel, aiRiskScore, requiredApprovals,
                currentReviewStage, errorMessage, executionStartedAt, executionCompletedAt, createdAt,
                updatedAt, items, null);
    }
}
