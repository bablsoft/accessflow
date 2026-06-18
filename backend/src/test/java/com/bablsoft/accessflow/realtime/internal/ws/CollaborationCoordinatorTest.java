package com.bablsoft.accessflow.realtime.internal.ws;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.realtime.internal.ws.CollaborationRoomRegistry.JoinResult;
import com.bablsoft.accessflow.realtime.internal.ws.CollaborationRoomRegistry.Member;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.CollaboratorIdentity;
import com.bablsoft.accessflow.workflow.api.QueryCollaborationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CollaborationCoordinatorTest {

    private final CollaborationRoomRegistry roomRegistry = mock(CollaborationRoomRegistry.class);
    private final QueryCollaborationAccessService accessService =
            mock(QueryCollaborationAccessService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T10:00:00Z"), ZoneOffset.UTC);

    private CollaborationCoordinator coordinator;

    private final UUID queryId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final JwtClaims claims =
            new JwtClaims(userId, "ann@example.com", UserRoleType.REVIEWER, orgId);

    @BeforeEach
    void setUp() {
        coordinator = new CollaborationCoordinator(roomRegistry, accessService, objectMapper, clock);
    }

    @Test
    void joinAuthorizedAddsToRoomAndAcknowledgesJoiner() throws Exception {
        var joiner = openSession("s1");
        when(accessService.resolveParticipant(queryId, userId, orgId, UserRoleType.REVIEWER))
                .thenReturn(Optional.of(new CollaboratorIdentity(userId, "Ann")));
        when(roomRegistry.join(eq(queryId), eq(joiner), eq(userId), eq("Ann"), anyString()))
                .thenReturn(new JoinResult(true, List.of(new Member(userId, "Ann", "#2563eb"))));
        when(roomRegistry.sessionsExcept(queryId, "s1")).thenReturn(List.of());

        coordinator.handle(joiner, claims, join(queryId));

        var msg = captureSend(joiner);
        assertThat(msg).contains("collab.joined").contains("\"seed\":true")
                .contains(queryId.toString());
    }

    @Test
    void joinDeniedSendsDeniedAndDoesNotJoin() throws Exception {
        var joiner = openSession("s1");
        when(accessService.resolveParticipant(queryId, userId, orgId, UserRoleType.REVIEWER))
                .thenReturn(Optional.empty());

        coordinator.handle(joiner, claims, join(queryId));

        assertThat(captureSend(joiner)).contains("collab.denied").contains("NOT_PERMITTED");
        verify(roomRegistry, never()).join(any(), any(), any(), any(), any());
    }

    @Test
    void syncRelaysToOtherMembersOnly() throws Exception {
        var sender = openSession("s1");
        var peer = openSession("s2");
        when(roomRegistry.isMember(queryId, sender)).thenReturn(true);
        when(roomRegistry.sessionsExcept(queryId, "s1")).thenReturn(List.of(peer));

        coordinator.handle(sender, claims,
                "{\"type\":\"collab.sync\",\"query_id\":\"" + queryId + "\",\"update\":\"AQID\"}");

        var msg = captureSend(peer);
        assertThat(msg).contains("collab.sync").contains("AQID")
                .contains(userId.toString());
        verify(sender, never()).sendMessage(any());
    }

    @Test
    void syncIgnoredWhenSenderNotAMember() {
        var sender = openSession("s1");
        when(roomRegistry.isMember(queryId, sender)).thenReturn(false);

        coordinator.handle(sender, claims,
                "{\"type\":\"collab.sync\",\"query_id\":\"" + queryId + "\",\"update\":\"AQID\"}");

        verify(roomRegistry, never()).sessionsExcept(any(), anyString());
    }

    @Test
    void awarenessRelaysToPeers() throws Exception {
        var sender = openSession("s1");
        var peer = openSession("s2");
        when(roomRegistry.isMember(queryId, sender)).thenReturn(true);
        when(roomRegistry.sessionsExcept(queryId, "s1")).thenReturn(List.of(peer));

        coordinator.handle(sender, claims,
                "{\"type\":\"collab.awareness\",\"query_id\":\"" + queryId + "\",\"update\":\"BQY=\"}");

        assertThat(captureSend(peer)).contains("collab.awareness").contains("BQY=");
    }

    @Test
    void leaveBroadcastsPresenceToRemaining() throws Exception {
        var sender = openSession("s1");
        var peer = openSession("s2");
        when(roomRegistry.leave(queryId, sender))
                .thenReturn(List.of(new Member(UUID.randomUUID(), "Bob", "#000")));
        when(roomRegistry.sessionsExcept(queryId, "s1")).thenReturn(List.of(peer));

        coordinator.handle(sender, claims,
                "{\"type\":\"collab.leave\",\"query_id\":\"" + queryId + "\"}");

        assertThat(captureSend(peer)).contains("collab.presence");
    }

    @Test
    void onSessionClosedBroadcastsPresenceForEachAffectedRoom() throws Exception {
        var closed = openSession("s1");
        var peer = openSession("s2");
        when(roomRegistry.removeSession(closed)).thenReturn(Set.of(queryId));
        when(roomRegistry.roster(queryId))
                .thenReturn(List.of(new Member(UUID.randomUUID(), "Bob", "#000")));
        when(roomRegistry.sessionsExcept(queryId, "s1")).thenReturn(List.of(peer));

        coordinator.onSessionClosed(closed);

        assertThat(captureSend(peer)).contains("collab.presence");
    }

    @Test
    void malformedFrameIsSwallowed() {
        var sender = openSession("s1");

        coordinator.handle(sender, claims, "not-json");

        verifyNoInteractions(roomRegistry);
    }

    @Test
    void unknownFrameTypeIsIgnored() {
        var sender = openSession("s1");

        coordinator.handle(sender, claims,
                "{\"type\":\"collab.bogus\",\"query_id\":\"" + queryId + "\"}");

        verifyNoInteractions(roomRegistry, accessService);
    }

    private String join(UUID queryId) {
        return "{\"type\":\"collab.join\",\"query_id\":\"" + queryId + "\"}";
    }

    private static String captureSend(WebSocketSession session) throws Exception {
        var captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        return captor.getValue().getPayload();
    }

    private static WebSocketSession openSession(String id) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
