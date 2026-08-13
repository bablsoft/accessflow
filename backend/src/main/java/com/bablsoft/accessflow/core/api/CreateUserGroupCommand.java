package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public record CreateUserGroupCommand(
        UUID organizationId,
        String name,
        String description,
        String scimExternalId
) {
    /** Convenience constructor for callers that predate the SCIM external id (#621). */
    public CreateUserGroupCommand(UUID organizationId, String name, String description) {
        this(organizationId, name, description, null);
    }
}
