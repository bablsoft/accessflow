package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Size;

public record UpdateUserGroupRequest(
        @Size(min = 1, max = 128, message = "{validation.group.name.size}") String name,
        @Size(max = 512, message = "{validation.group.description.size}") String description
) {}
