package com.bablsoft.accessflow.core.api;

import java.util.Set;

/** Partial update of a custom role — {@code null} fields are left unchanged. */
public record UpdateRoleCommand(
        String name,
        String description,
        Set<Permission> permissions
) {
}
