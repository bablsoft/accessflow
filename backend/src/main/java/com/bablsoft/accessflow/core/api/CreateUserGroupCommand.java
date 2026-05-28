package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public record CreateUserGroupCommand(
        UUID organizationId,
        String name,
        String description
) {}
