package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.SessionRevocationService;
import com.bablsoft.accessflow.security.internal.token.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultSessionRevocationService implements SessionRevocationService {

    private final RefreshTokenStore refreshTokenStore;

    @Override
    public void revokeAllSessions(UUID userId) {
        refreshTokenStore.revokeAllForUser(userId.toString());
    }
}
