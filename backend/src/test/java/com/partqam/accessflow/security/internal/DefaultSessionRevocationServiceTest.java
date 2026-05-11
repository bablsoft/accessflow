package com.partqam.accessflow.security.internal;

import com.partqam.accessflow.security.internal.token.RefreshTokenStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DefaultSessionRevocationServiceTest {

    @Mock RefreshTokenStore refreshTokenStore;
    @InjectMocks DefaultSessionRevocationService service;

    @Test
    void revokeAllSessionsDelegatesToTokenStore() {
        var userId = UUID.randomUUID();
        service.revokeAllSessions(userId);
        verify(refreshTokenStore).revokeAllForUser(userId.toString());
    }
}
