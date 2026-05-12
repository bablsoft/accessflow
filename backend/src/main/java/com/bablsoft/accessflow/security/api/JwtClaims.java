package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.UUID;

public record JwtClaims(
        UUID userId,
        String email,
        UserRoleType role,
        UUID organizationId
) {}
