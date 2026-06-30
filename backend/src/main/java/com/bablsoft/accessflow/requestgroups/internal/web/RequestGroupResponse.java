package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record RequestGroupResponse(
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
        List<RequestGroupItemResponse> items) {

    static RequestGroupResponse from(RequestGroupView v) {
        return new RequestGroupResponse(v.id(), v.organizationId(), v.submittedByUserId(),
                v.submittedByDisplayName(), v.name(), v.description(), v.status(), v.continueOnError(),
                v.scheduledFor(), v.aiRiskLevel(), v.aiRiskScore(), v.requiredApprovals(),
                v.currentReviewStage(), v.errorMessage(), v.executionStartedAt(),
                v.executionCompletedAt(), v.createdAt(), v.updatedAt(),
                v.items().stream().map(RequestGroupItemResponse::from).toList());
    }
}
