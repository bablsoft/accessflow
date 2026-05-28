package com.bablsoft.accessflow.security.internal.web.model;

import java.util.UUID;

public record CreateDatasourceReviewerRequest(
        UUID userId,
        UUID groupId
) {}
