package com.bablsoft.accessflow.core.api;

/**
 * Command to create a new organization from the platform management UI (AF-456). {@code requestedSlug}
 * is optional — when blank, a slug is derived from {@code name}. Quota fields are nullable
 * (null/0 = unlimited).
 */
public record CreateOrganizationCommand(
        String name,
        String requestedSlug,
        Integer maxDatasources,
        Integer maxUsers,
        Integer maxQueriesPerDay
) {}
