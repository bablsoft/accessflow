package com.bablsoft.accessflow.core.api;

import java.util.Set;
import java.util.UUID;

public record CreateRoleCommand(
        UUID organizationId,
        String name,
        String description,
        Set<Permission> permissions
) {
}
