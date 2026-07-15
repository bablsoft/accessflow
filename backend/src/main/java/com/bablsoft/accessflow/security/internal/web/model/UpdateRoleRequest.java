package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.Permission;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateRoleRequest(
        @Size(max = 100, message = "{validation.role_name.max}") String name,
        @Size(max = 500, message = "{validation.role_description.max}") String description,
        Set<Permission> permissions
) {}
