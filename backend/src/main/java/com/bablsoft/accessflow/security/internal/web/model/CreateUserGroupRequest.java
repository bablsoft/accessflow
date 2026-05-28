package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserGroupRequest(
        @NotBlank(message = "{validation.group.name.required}")
        @Size(min = 1, max = 128, message = "{validation.group.name.size}") String name,
        @Size(max = 512, message = "{validation.group.description.size}") String description
) {}
