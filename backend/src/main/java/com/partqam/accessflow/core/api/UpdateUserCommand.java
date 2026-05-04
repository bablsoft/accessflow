package com.partqam.accessflow.core.api;

public record UpdateUserCommand(
        UserRoleType role,
        Boolean active,
        String displayName
) {}
