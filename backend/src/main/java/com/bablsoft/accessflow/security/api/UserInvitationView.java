package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.UUID;

public record UserInvitationView(
        UUID id,
        UUID organizationId,
        String email,
        String displayName,
        UserRoleType role,
        UserInvitationStatusType status,
        Instant expiresAt,
        Instant acceptedAt,
        Instant revokedAt,
        UUID invitedByUserId,
        Instant createdAt
) {}
