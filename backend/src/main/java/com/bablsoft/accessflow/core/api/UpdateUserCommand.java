package com.bablsoft.accessflow.core.api;

public record UpdateUserCommand(
        UserRoleType role,
        Boolean active,
        String displayName
) {}
