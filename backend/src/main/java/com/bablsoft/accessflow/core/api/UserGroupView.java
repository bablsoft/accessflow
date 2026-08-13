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
        Instant updatedAt,
        String scimExternalId
) {
    /** Convenience constructor for callers that predate the SCIM external id (#621). */
    public UserGroupView(UUID id, UUID organizationId, String name, String description,
                         long memberCount, Instant createdAt, Instant updatedAt) {
        this(id, organizationId, name, description, memberCount, createdAt, updatedAt, null);
    }
}
