package com.bablsoft.accessflow.core.api;

public record UpdateUserGroupCommand(
        String name,
        String description,
        String scimExternalId
) {
    /** Convenience constructor for callers that predate the SCIM external id (#621). */
    public UpdateUserGroupCommand(String name, String description) {
        this(name, description, null);
    }
}
