package com.bablsoft.accessflow.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QueryTemplateView(
        UUID id,
        UUID organizationId,
        UUID ownerId,
        String ownerDisplayName,
        UUID datasourceId,
        String name,
        String body,
        String description,
        List<String> tags,
        QueryTemplateVisibility visibility,
        Instant createdAt,
        Instant updatedAt
) {}
