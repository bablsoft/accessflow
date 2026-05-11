package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.UserView;

import java.util.UUID;

public record MeProfileResponse(
        UUID id,
        String email,
        String displayName,
        String role,
        String authProvider,
        boolean totpEnabled,
        String preferredLanguage
) {
    public static MeProfileResponse from(UserView view) {
        return new MeProfileResponse(
                view.id(),
                view.email(),
                view.displayName(),
                view.role().name(),
                view.authProvider().name(),
                view.totpEnabled(),
                view.preferredLanguage()
        );
    }
}
