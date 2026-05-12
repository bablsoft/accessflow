package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public record CreateUserCommand(
        UUID organizationId,
        String email,
        String displayName,
        String passwordHash,
        UserRoleType role
) {}
