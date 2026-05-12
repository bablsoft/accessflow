package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public interface UserProfileService {

    UserView getProfile(UUID userId);

    UserView updateDisplayName(UUID userId, String displayName);

    void changePassword(UUID userId, String currentPassword, String newPassword);

    TotpEnrollment startTotpEnrollment(UUID userId);

    TotpConfirmationResult confirmTotpEnrollment(UUID userId, String code);

    void disableTotp(UUID userId, String currentPassword);
}
