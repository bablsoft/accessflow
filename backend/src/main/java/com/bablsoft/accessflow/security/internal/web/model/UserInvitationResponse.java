package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.UserInvitationStatusType;
import com.bablsoft.accessflow.security.api.UserInvitationView;

import java.time.Instant;
import java.util.UUID;

public record UserInvitationResponse(
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
) {

    public static UserInvitationResponse from(UserInvitationView view) {
        return new UserInvitationResponse(
                view.id(),
                view.organizationId(),
                view.email(),
                view.displayName(),
                view.role(),
                view.status(),
                view.expiresAt(),
                view.acceptedAt(),
                view.revokedAt(),
                view.invitedByUserId(),
                view.createdAt());
    }
}
