package com.partqam.accessflow.realtime.internal.ws;

import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeWebSocketHandlerTest {

    @Mock SessionRegistry sessionRegistry;
    @Mock WebSocketSession session;

    @InjectMocks RealtimeWebSocketHandler handler;

    @Test
    void afterConnectionEstablishedRegistersSessionWithUserIdFromClaims() throws Exception {
        var userId = UUID.randomUUID();
        var claims = new JwtClaims(userId, "u@example.com", UserRoleType.ANALYST,
                UUID.randomUUID());
        when(session.getAttributes()).thenReturn(attrs(claims));

        handler.afterConnectionEstablished(session);

        verify(sessionRegistry).register(userId, session);
    }

    @Test
    void afterConnectionEstablishedClosesSessionWhenClaimsMissing() throws Exception {
        when(session.getAttributes()).thenReturn(new HashMap<>());

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(sessionRegistry);
    }

    @Test
    void afterConnectionClosedUnregistersByUserId() {
        var userId = UUID.randomUUID();
        var claims = new JwtClaims(userId, "u@example.com", UserRoleType.ANALYST,
                UUID.randomUUID());
        when(session.getAttributes()).thenReturn(attrs(claims));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(sessionRegistry).unregister(userId, session);
    }

    @Test
    void afterConnectionClosedIsNoOpWhenClaimsMissing() {
        when(session.getAttributes()).thenReturn(new HashMap<>());

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verifyNoInteractions(sessionRegistry);
    }

    private static Map<String, Object> attrs(JwtClaims claims) {
        var map = new HashMap<String, Object>();
        map.put(JwtHandshakeInterceptor.ATTR_CLAIMS, claims);
        return map;
    }
}
