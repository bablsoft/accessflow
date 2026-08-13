package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.events.UserDeactivatedEvent;
import com.bablsoft.accessflow.security.internal.token.RefreshTokenStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserDeactivationListenerTest {

    @Mock RefreshTokenStore refreshTokenStore;

    @Test
    void revokesAllRefreshTokensForDeactivatedUser() {
        var listener = new UserDeactivationListener(refreshTokenStore);
        var userId = UUID.randomUUID();

        listener.onUserDeactivated(new UserDeactivatedEvent(userId, UUID.randomUUID()));

        verify(refreshTokenStore).revokeAllForUser(userId.toString());
    }
}
