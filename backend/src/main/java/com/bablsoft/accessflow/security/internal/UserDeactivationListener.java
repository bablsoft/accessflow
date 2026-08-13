package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.events.UserDeactivatedEvent;
import com.bablsoft.accessflow.security.internal.token.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Revokes every refresh token of a user the moment they are deactivated — whatever the initiating
 * path (admin update, admin delete, SCIM deprovisioning). The user's outstanding access tokens
 * expire naturally within {@code ACCESSFLOW_JWT_ACCESS_TOKEN_EXPIRY} (default 15 minutes).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class UserDeactivationListener {

    private final RefreshTokenStore refreshTokenStore;

    @ApplicationModuleListener
    void onUserDeactivated(UserDeactivatedEvent event) {
        refreshTokenStore.revokeAllForUser(event.userId().toString());
        log.info("Revoked all refresh tokens for deactivated user {}", event.userId());
    }
}
