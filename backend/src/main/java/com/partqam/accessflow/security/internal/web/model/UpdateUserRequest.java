package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.UserRoleType;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        UserRoleType role,
        Boolean active,
        @Size(max = 255) String displayName
) {}
