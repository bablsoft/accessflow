package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record DatasourceReviewerView(
        UUID id,
        UUID datasourceId,
        UUID userId,
        String userEmail,
        String userDisplayName,
        UUID groupId,
        String groupName,
        UUID createdBy,
        Instant createdAt
) {}
