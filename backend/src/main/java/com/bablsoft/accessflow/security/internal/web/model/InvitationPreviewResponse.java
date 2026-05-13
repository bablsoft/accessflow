package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.InvitationPreview;

import java.time.Instant;

public record InvitationPreviewResponse(
        String email,
        String displayName,
        UserRoleType role,
        String organizationName,
        Instant expiresAt
) {

    public static InvitationPreviewResponse from(InvitationPreview preview) {
        return new InvitationPreviewResponse(
                preview.email(),
                preview.displayName(),
                preview.role(),
                preview.organizationName(),
                preview.expiresAt());
    }
}
