package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.Permission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateRoleRequest(
        @NotBlank(message = "{validation.role_name.required}")
        @Size(max = 100, message = "{validation.role_name.max}") String name,
        @Size(max = 500, message = "{validation.role_description.max}") String description,
        @NotNull(message = "{validation.role_permissions.required}") Set<Permission> permissions
) {}
