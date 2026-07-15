package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RoleView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoleResponse(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        boolean system,
        List<String> permissions,
        long assignedUserCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static RoleResponse from(RoleView view) {
        return new RoleResponse(
                view.id(),
                view.organizationId(),
                view.name(),
                view.description(),
                view.system(),
                view.permissions().stream().map(Permission::name).sorted().toList(),
                view.assignedUserCount(),
                view.createdAt(),
                view.updatedAt());
    }
}
