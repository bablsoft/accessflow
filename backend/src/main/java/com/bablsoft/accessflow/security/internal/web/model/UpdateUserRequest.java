package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserRoleType;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record UpdateUserRequest(
        UserRoleType role,
        UUID roleId,
        Boolean active,
        @Size(max = 255, message = "{validation.display_name.max}") String displayName,
        @Size(max = 50, message = "{validation.user_attributes.max}")
        Map<String, String> attributes
) {}
