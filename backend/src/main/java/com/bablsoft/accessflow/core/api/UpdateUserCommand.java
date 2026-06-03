package com.bablsoft.accessflow.core.api;

import java.util.Map;

public record UpdateUserCommand(
        UserRoleType role,
        Boolean active,
        String displayName,
        Map<String, String> attributes
) {}
