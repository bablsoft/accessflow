package com.bablsoft.accessflow.realtime.internal.ws;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
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
    @Mock CollaborationCoordinator collaborationCoordinator;
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
    void handleTextMessageRoutesToCollaborationCoordinator() {
        var claims = new JwtClaims(UUID.randomUUID(), "u@example.com", UserRoleType.REVIEWER,
                UUID.randomUUID());
        when(session.getAttributes()).thenReturn(attrs(claims));
        var payload = "{\"type\":\"collab.join\",\"query_id\":\"" + UUID.randomUUID() + "\"}";

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(collaborationCoordinator).handle(session, claims, payload);
    }

    @Test
    void handleTextMessageIgnoredWhenClaimsMissing() {
        when(session.getAttributes()).thenReturn(new HashMap<>());

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"collab.join\"}"));

        verifyNoInteractions(collaborationCoordinator);
    }

    @Test
    void afterConnectionClosedUnregistersAndEvictsFromRooms() {
        var userId = UUID.randomUUID();
        var claims = new JwtClaims(userId, "u@example.com", UserRoleType.ANALYST,
                UUID.randomUUID());
        when(session.getAttributes()).thenReturn(attrs(claims));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(sessionRegistry).unregister(userId, session);
        verify(collaborationCoordinator).onSessionClosed(session);
    }

    @Test
    void afterConnectionClosedStillEvictsRoomsWhenClaimsMissing() {
        when(session.getAttributes()).thenReturn(new HashMap<>());

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verifyNoInteractions(sessionRegistry);
        verify(collaborationCoordinator).onSessionClosed(session);
    }

    private static Map<String, Object> attrs(JwtClaims claims) {
        var map = new HashMap<String, Object>();
        map.put(JwtHandshakeInterceptor.ATTR_CLAIMS, claims);
        return map;
    }
}
