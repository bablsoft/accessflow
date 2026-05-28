package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record UserGroupView(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        long memberCount,
        Instant createdAt,
        Instant updatedAt
) {}
