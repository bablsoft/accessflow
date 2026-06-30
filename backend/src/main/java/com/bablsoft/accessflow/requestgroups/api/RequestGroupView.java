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
        List<RequestGroupItemView> items) {
}
