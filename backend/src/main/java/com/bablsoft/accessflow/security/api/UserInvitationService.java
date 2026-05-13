package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Email-based user invitation flow. Invitations are single-use tokens delivered through the
 * organization's system SMTP and accepted by setting a password.
 */
public interface UserInvitationService {

    IssuedInvitation invite(InviteUserCommand command, UUID organizationId, UUID invitedByUserId);

    PageResponse<UserInvitationView> list(UUID organizationId, PageRequest pageRequest);

    void revoke(UUID invitationId, UUID organizationId);

    IssuedInvitation resend(UUID invitationId, UUID organizationId, UUID invitedByUserId);

    InvitationPreview previewByToken(String plaintextToken);

    AcceptedInvitation acceptInvitation(String plaintextToken, String plaintextPassword, String displayName);
}
