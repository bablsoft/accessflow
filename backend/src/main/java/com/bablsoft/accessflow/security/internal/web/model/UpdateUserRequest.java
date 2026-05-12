package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserRoleType;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        UserRoleType role,
        Boolean active,
        @Size(max = 255, message = "{validation.display_name.max}") String displayName
) {}
