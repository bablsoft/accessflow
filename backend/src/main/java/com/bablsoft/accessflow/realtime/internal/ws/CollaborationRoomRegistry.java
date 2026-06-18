package com.bablsoft.accessflow.realtime.internal.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks query-scoped collaboration "rooms": which WebSocket sessions are co-authoring which query.
 * A room is created on the first authorized {@code collab.join} and dropped when its last
 * participant leaves, so memory is bounded by live collaboration. The backend is an opaque relay —
 * it never inspects the Yjs document; it only needs to know who to forward updates to.
 *
 * <p>Thread-safety: the per-room participant map is a {@link ConcurrentHashMap}; "was the room empty
 * before this join" and the empty-room drop are guarded by synchronizing on that map instance.
 */
@Component
public class CollaborationRoomRegistry {

    /** A connected co-author. Multiple sessions (tabs) of the same user are distinct participants. */
    public record Participant(UUID userId, String displayName, String color,
                              WebSocketSession session) {
    }

    /** A roster entry deduplicated by user (one row per user regardless of tab count). */
    public record Member(UUID userId, String displayName, String color) {
    }

    public record JoinResult(boolean firstParticipant, List<Member> roster) {
    }

    private final ConcurrentMap<UUID, Map<String, Participant>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<UUID>> roomsBySession = new ConcurrentHashMap<>();

    public JoinResult join(UUID queryId, WebSocketSession session, UUID userId, String displayName,
                           String color) {
        var room = rooms.computeIfAbsent(queryId, id -> new ConcurrentHashMap<>());
        boolean firstParticipant;
        synchronized (room) {
            firstParticipant = room.isEmpty();
            room.put(session.getId(), new Participant(userId, displayName, color, session));
        }
        roomsBySession.computeIfAbsent(session.getId(), id -> ConcurrentHashMap.newKeySet())
                .add(queryId);
        return new JoinResult(firstParticipant, roster(queryId));
    }

    /** Removes the session from one room; returns the remaining roster (empty if the room is gone). */
    public List<Member> leave(UUID queryId, WebSocketSession session) {
        var room = rooms.get(queryId);
        if (room != null) {
            synchronized (room) {
                room.remove(session.getId());
                if (room.isEmpty()) {
                    rooms.remove(queryId, room);
                }
            }
        }
        var sessionRooms = roomsBySession.get(session.getId());
        if (sessionRooms != null) {
            sessionRooms.remove(queryId);
            if (sessionRooms.isEmpty()) {
                roomsBySession.remove(session.getId(), sessionRooms);
            }
        }
        return roster(queryId);
    }

    /** Removes a closed session from every room it joined; returns the affected query ids. */
    public Set<UUID> removeSession(WebSocketSession session) {
        var sessionRooms = roomsBySession.remove(session.getId());
        if (sessionRooms == null) {
            return Set.of();
        }
        var affected = Set.copyOf(sessionRooms);
        for (var queryId : affected) {
            var room = rooms.get(queryId);
            if (room == null) {
                continue;
            }
            synchronized (room) {
                room.remove(session.getId());
                if (room.isEmpty()) {
                    rooms.remove(queryId, room);
                }
            }
        }
        return affected;
    }

    public boolean isMember(UUID queryId, WebSocketSession session) {
        var room = rooms.get(queryId);
        return room != null && room.containsKey(session.getId());
    }

    public List<WebSocketSession> sessions(UUID queryId) {
        var room = rooms.get(queryId);
        if (room == null) {
            return List.of();
        }
        return new ArrayList<>(room.values()).stream().map(Participant::session).toList();
    }

    public List<WebSocketSession> sessionsExcept(UUID queryId, String excludedSessionId) {
        var room = rooms.get(queryId);
        if (room == null) {
            return List.of();
        }
        var result = new ArrayList<WebSocketSession>();
        for (var entry : room.entrySet()) {
            if (!entry.getKey().equals(excludedSessionId)) {
                result.add(entry.getValue().session());
            }
        }
        return result;
    }

    /** Roster deduplicated by user, preserving first-seen order. */
    public List<Member> roster(UUID queryId) {
        var room = rooms.get(queryId);
        if (room == null) {
            return List.of();
        }
        var byUser = new LinkedHashMap<UUID, Member>();
        for (var p : room.values()) {
            byUser.putIfAbsent(p.userId(), new Member(p.userId(), p.displayName(), p.color()));
        }
        return new ArrayList<>(byUser.values());
    }
}
