package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public interface UserProfileService {

    UserView getProfile(UUID userId);

    UserView updateDisplayName(UUID userId, String displayName);

    void changePassword(UUID userId, String currentPassword, String newPassword);

    /**
     * Set a new password for a LOCAL account without verifying the current one. Used by the
     * password-reset flow when the user proves account ownership by clicking an emailed token
     * link. Encodes the password and revokes all active sessions on success.
     */
    void resetPassword(UUID userId, String newPassword);

    TotpEnrollment startTotpEnrollment(UUID userId);

    TotpConfirmationResult confirmTotpEnrollment(UUID userId, String code);

    void disableTotp(UUID userId, String currentPassword);
}
