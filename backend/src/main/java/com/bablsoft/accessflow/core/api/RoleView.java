package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A role visible to an organization: either one of the 5 global immutable system roles
 * ({@code organizationId == null}, {@code system == true}) or an org-scoped custom role (AF-522).
 */
public record RoleView(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        boolean system,
        Set<Permission> permissions,
        long assignedUserCount,
        Instant createdAt,
        Instant updatedAt
) {
}
