package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;

public record InvitationPreview(
        String email,
        String displayName,
        UserRoleType role,
        String organizationName,
        Instant expiresAt
) {}
