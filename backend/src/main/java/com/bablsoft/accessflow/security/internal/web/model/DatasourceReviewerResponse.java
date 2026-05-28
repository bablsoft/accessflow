package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DatasourceReviewerView;

import java.time.Instant;
import java.util.UUID;

public record DatasourceReviewerResponse(
        UUID id,
        UUID datasourceId,
        UUID userId,
        String userEmail,
        String userDisplayName,
        UUID groupId,
        String groupName,
        UUID createdBy,
        Instant createdAt
) {
    public static DatasourceReviewerResponse from(DatasourceReviewerView view) {
        return new DatasourceReviewerResponse(
                view.id(),
                view.datasourceId(),
                view.userId(),
                view.userEmail(),
                view.userDisplayName(),
                view.groupId(),
                view.groupName(),
                view.createdBy(),
                view.createdAt()
        );
    }
}
