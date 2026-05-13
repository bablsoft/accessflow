package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

public record InviteUserCommand(
        String email,
        String displayName,
        UserRoleType role
) {}
