package com.partqam.accessflow.security.api;

import com.partqam.accessflow.core.api.UserRoleType;

import java.util.UUID;

public record JwtClaims(
        UUID userId,
        String email,
        UserRoleType role,
        UUID organizationId
) {}
