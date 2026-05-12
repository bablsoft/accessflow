package com.bablsoft.accessflow.realtime.internal.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRegistryTest {

    private SessionRegistry registry;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry();
    }

    @Test
    void registerAndUnregisterTracksSessionCount() {
        var session = openSession();

        registry.register(userId, session);
        assertThat(registry.sessionCount(userId)).isEqualTo(1);

        registry.unregister(userId, session);
        assertThat(registry.sessionCount(userId)).isZero();
    }

    @Test
    void sendToUserDeliversToEveryActiveSession() throws Exception {
        var s1 = openSession();
        var s2 = openSession();
        registry.register(userId, s1);
        registry.register(userId, s2);

        registry.sendToUser(userId, "{}");

        verify(s1).sendMessage(any(TextMessage.class));
        verify(s2).sendMessage(any(TextMessage.class));
    }

    @Test
    void sendToUserSilentlyDropsClosedSessions() throws Exception {
        var open = openSession();
        var closed = mock(WebSocketSession.class);
        when(closed.isOpen()).thenReturn(false);
        registry.register(userId, open);
        registry.register(userId, closed);

        registry.sendToUser(userId, "{}");

        verify(open).sendMessage(any(TextMessage.class));
        verify(closed, never()).sendMessage(any());
        assertThat(registry.sessionCount(userId)).isEqualTo(1);
    }

    @Test
    void sendToUserDropsSessionWhenSendThrows() throws Exception {
        var session = openSession();
        doThrow(new IOException("network down")).when(session).sendMessage(any());
        registry.register(userId, session);

        registry.sendToUser(userId, "{}");

        assertThat(registry.sessionCount(userId)).isZero();
    }

    @Test
    void sendToUserIsNoOpWhenUserHasNoSessions() {
        // Should not throw even when no sessions registered for that user.
        registry.sendToUser(UUID.randomUUID(), "{}");
        assertThat(registry.sessionCount(userId)).isZero();
    }

    private WebSocketSession openSession() {
        var session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
