package com.bablsoft.accessflow.realtime.internal.ws;

import com.bablsoft.accessflow.realtime.internal.ws.CollaborationRoomRegistry.JoinResult;
import com.bablsoft.accessflow.realtime.internal.ws.CollaborationRoomRegistry.Member;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.QueryCollaborationAccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Server side of the bidirectional collaboration protocol over {@code /ws}. Parses inbound client
 * frames ({@code collab.join|leave|sync|awareness}), authorizes joins through
 * {@link QueryCollaborationAccessService}, and relays opaque Yjs document/awareness updates to the
 * other members of a query's room. The backend never parses the Yjs payload — it is forwarded
 * verbatim. Late-joiner state convergence is handled client-side: the first joiner of a fresh room
 * seeds the document from the query's SQL (signalled by {@code seed}), peers exchange full state on
 * presence change. See {@code docs/05-backend.md}.
 */
@Component
@Slf4j
public class CollaborationCoordinator {

    // A small fixed palette; each user gets a stable colour derived from their id so cursors stay
    // recognisable across sessions.
    private static final String[] PALETTE = {
            "#2563eb", "#dc2626", "#16a34a", "#d97706",
            "#7c3aed", "#0891b2", "#db2777", "#65a30d"
    };

    private final CollaborationRoomRegistry roomRegistry;
    private final QueryCollaborationAccessService collaborationAccessService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public CollaborationCoordinator(CollaborationRoomRegistry roomRegistry,
                                    QueryCollaborationAccessService collaborationAccessService,
                                    ObjectMapper objectMapper) {
        this(roomRegistry, collaborationAccessService, objectMapper, Clock.systemUTC());
    }

    // Package-private constructor for tests that need a fixed clock.
    CollaborationCoordinator(CollaborationRoomRegistry roomRegistry,
                             QueryCollaborationAccessService collaborationAccessService,
                             ObjectMapper objectMapper, Clock clock) {
        this.roomRegistry = roomRegistry;
        this.collaborationAccessService = collaborationAccessService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Routes one inbound client text frame. Never throws — a bad frame is logged and dropped. */
    public void handle(WebSocketSession session, JwtClaims claims, String payload) {
        try {
            var node = objectMapper.readTree(payload);
            var type = text(node, "type");
            switch (type) {
                case "collab.join" -> onJoin(session, claims, queryId(node));
                case "collab.leave" -> onLeave(session, queryId(node));
                case "collab.sync" -> relay(session, claims, node, "collab.sync");
                case "collab.awareness" -> relay(session, claims, node, "collab.awareness");
                default -> log.debug("Ignoring unknown collab frame type '{}'", type);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to handle collaboration frame from session {}: {}", session.getId(),
                    ex.getMessage());
        }
    }

    /** Evicts a closed session from every room and notifies the survivors. */
    public void onSessionClosed(WebSocketSession session) {
        for (var queryId : roomRegistry.removeSession(session)) {
            broadcastPresence(queryId, session.getId());
        }
    }

    private void onJoin(WebSocketSession session, JwtClaims claims, UUID queryId) {
        if (queryId == null) {
            return;
        }
        var identity = collaborationAccessService.resolveParticipant(queryId, claims.userId(),
                claims.organizationId(), claims.roleName(), claims.permissions()).orElse(null);
        if (identity == null) {
            sendDenied(session, queryId);
            return;
        }
        var color = colorFor(claims.userId());
        var displayName = identity.displayName();
        JoinResult result = roomRegistry.join(queryId, session, claims.userId(), displayName, color);

        var joined = newEnvelope("collab.joined");
        var joinedData = objectMapper.createObjectNode();
        joinedData.put("query_id", queryId.toString());
        joinedData.put("seed", result.firstParticipant());
        var self = objectMapper.createObjectNode();
        self.put("user_id", claims.userId().toString());
        self.put("display_name", displayName);
        self.put("color", color);
        joinedData.set("self", self);
        joinedData.set("participants", participantsArray(result.roster()));
        joined.set("data", joinedData);
        send(session, joined.toString());

        // Tell everyone else the roster grew.
        broadcastPresence(queryId, session.getId());
    }

    private void onLeave(WebSocketSession session, UUID queryId) {
        if (queryId == null) {
            return;
        }
        roomRegistry.leave(queryId, session);
        broadcastPresence(queryId, session.getId());
    }

    private void relay(WebSocketSession session, JwtClaims claims, JsonNode node, String eventName) {
        var queryId = queryId(node);
        if (queryId == null || !roomRegistry.isMember(queryId, session)) {
            return;
        }
        var update = text(node, "update");
        if (update.isEmpty()) {
            return;
        }
        var envelope = newEnvelope(eventName);
        var data = objectMapper.createObjectNode();
        data.put("query_id", queryId.toString());
        data.put("from_user_id", claims.userId().toString());
        data.put("update", update);
        envelope.set("data", data);
        var json = envelope.toString();
        for (var peer : roomRegistry.sessionsExcept(queryId, session.getId())) {
            send(peer, json);
        }
    }

    private void broadcastPresence(UUID queryId, String excludedSessionId) {
        var roster = roomRegistry.roster(queryId);
        var envelope = newEnvelope("collab.presence");
        var data = objectMapper.createObjectNode();
        data.put("query_id", queryId.toString());
        data.set("participants", participantsArray(roster));
        envelope.set("data", data);
        var json = envelope.toString();
        for (var peer : roomRegistry.sessionsExcept(queryId, excludedSessionId)) {
            send(peer, json);
        }
    }

    private void sendDenied(WebSocketSession session, UUID queryId) {
        var envelope = newEnvelope("collab.denied");
        var data = objectMapper.createObjectNode();
        data.put("query_id", queryId.toString());
        data.put("reason", "NOT_PERMITTED");
        envelope.set("data", data);
        send(session, envelope.toString());
    }

    private ArrayNode participantsArray(List<Member> roster) {
        var array = objectMapper.createArrayNode();
        for (var member : roster) {
            var node = objectMapper.createObjectNode();
            node.put("user_id", member.userId().toString());
            node.put("display_name", member.displayName());
            node.put("color", member.color());
            array.add(node);
        }
        return array;
    }

    private ObjectNode newEnvelope(String eventName) {
        var envelope = objectMapper.createObjectNode();
        envelope.put("event", eventName);
        envelope.put("timestamp", Instant.now(clock).toString());
        return envelope;
    }

    private UUID queryId(JsonNode node) {
        var raw = text(node, "query_id");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : "";
    }

    private static String colorFor(UUID userId) {
        return PALETTE[Math.floorMod(userId.hashCode(), PALETTE.length)];
    }

    private void send(WebSocketSession session, String payload) {
        if (!session.isOpen()) {
            roomRegistry.removeSession(session);
            return;
        }
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(payload));
            } catch (IOException | IllegalStateException ex) {
                log.warn("Failed to relay collab frame to session {}: {}", session.getId(),
                        ex.getMessage());
                roomRegistry.removeSession(session);
            }
        }
    }
}
