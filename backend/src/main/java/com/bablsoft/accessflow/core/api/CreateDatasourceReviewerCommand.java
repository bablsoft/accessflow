package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public record CreateDatasourceReviewerCommand(
        UUID datasourceId,
        UUID organizationId,
        UUID createdBy,
        UUID userId,
        UUID groupId
) {}
