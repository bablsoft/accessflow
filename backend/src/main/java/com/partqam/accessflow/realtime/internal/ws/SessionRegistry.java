package com.partqam.accessflow.realtime.internal.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Tracks the currently-connected WebSocket sessions, keyed by user id, so the
 * realtime dispatcher can fan out events to a specific user (e.g. the submitter or a
 * single reviewer) without scanning every session on every push.
 *
 * <p>Spring requires that a single {@link WebSocketSession} only have one in-flight
 * {@code send} at a time; we synchronize on the session for that reason.
 */
@Component
@Slf4j
public class SessionRegistry {

    private final ConcurrentMap<UUID, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public void register(UUID userId, WebSocketSession session) {
        sessionsByUser
                .computeIfAbsent(userId, id -> new CopyOnWriteArraySet<>())
                .add(session);
    }

    public void unregister(UUID userId, WebSocketSession session) {
        var sessions = sessionsByUser.get(userId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByUser.remove(userId, sessions);
        }
    }

    /**
     * Sends {@code payload} to every currently-connected session for {@code userId}.
     * Failures are logged and the failing session is dropped from the registry; one
     * dead client never blocks delivery to the user's other tabs.
     */
    public void sendToUser(UUID userId, String payload) {
        var sessions = sessionsByUser.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (var session : sessions) {
            sendOne(userId, session, payload);
        }
    }

    public int sessionCount(UUID userId) {
        var sessions = sessionsByUser.get(userId);
        return sessions == null ? 0 : sessions.size();
    }

    private void sendOne(UUID userId, WebSocketSession session, String payload) {
        if (!session.isOpen()) {
            unregister(userId, session);
            return;
        }
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(payload));
            } catch (IOException | IllegalStateException ex) {
                log.warn("Failed to push WS message to user {}; dropping session {}: {}",
                        userId, session.getId(), ex.getMessage());
                unregister(userId, session);
                closeQuietly(session);
            }
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // Already failing — nothing more to do.
        }
    }
}
