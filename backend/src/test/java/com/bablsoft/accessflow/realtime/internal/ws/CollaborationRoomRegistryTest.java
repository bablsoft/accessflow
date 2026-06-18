package com.bablsoft.accessflow.realtime.internal.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollaborationRoomRegistryTest {

    private CollaborationRoomRegistry registry;
    private final UUID queryId = UUID.randomUUID();
    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        registry = new CollaborationRoomRegistry();
    }

    @Test
    void firstJoinIsFlaggedAsSeederSubsequentIsNot() {
        var first = registry.join(queryId, session("s1"), userA, "Ann", "#fff");
        assertThat(first.firstParticipant()).isTrue();
        assertThat(first.roster()).hasSize(1);

        var second = registry.join(queryId, session("s2"), userB, "Bob", "#000");
        assertThat(second.firstParticipant()).isFalse();
        assertThat(second.roster()).hasSize(2);
    }

    @Test
    void rosterDeduplicatesByUserAcrossTabs() {
        registry.join(queryId, session("s1"), userA, "Ann", "#fff");
        registry.join(queryId, session("s2"), userA, "Ann", "#fff");

        assertThat(registry.roster(queryId)).hasSize(1);
        assertThat(registry.roster(queryId).get(0).userId()).isEqualTo(userA);
    }

    @Test
    void leaveDropsRoomWhenLastParticipantLeaves() {
        var s1 = session("s1");
        registry.join(queryId, s1, userA, "Ann", "#fff");

        var roster = registry.leave(queryId, s1);

        assertThat(roster).isEmpty();
        assertThat(registry.roster(queryId)).isEmpty();
        assertThat(registry.isMember(queryId, s1)).isFalse();
    }

    @Test
    void sessionsExceptExcludesTheSender() {
        var s1 = session("s1");
        var s2 = session("s2");
        registry.join(queryId, s1, userA, "Ann", "#fff");
        registry.join(queryId, s2, userB, "Bob", "#000");

        var peers = registry.sessionsExcept(queryId, "s1");

        assertThat(peers).containsExactly(s2);
    }

    @Test
    void removeSessionEvictsFromEveryRoomItJoined() {
        var other = UUID.randomUUID();
        var s1 = session("s1");
        registry.join(queryId, s1, userA, "Ann", "#fff");
        registry.join(other, s1, userA, "Ann", "#fff");

        var affected = registry.removeSession(s1);

        assertThat(affected).containsExactlyInAnyOrder(queryId, other);
        assertThat(registry.roster(queryId)).isEmpty();
        assertThat(registry.roster(other)).isEmpty();
    }

    @Test
    void removeSessionForUnknownSessionReturnsEmpty() {
        assertThat(registry.removeSession(session("ghost"))).isEmpty();
    }

    @Test
    void rosterAndSessionsForUnknownRoomAreEmpty() {
        assertThat(registry.roster(UUID.randomUUID())).isEmpty();
        assertThat(registry.sessions(UUID.randomUUID())).isEmpty();
        assertThat(registry.sessionsExcept(UUID.randomUUID(), "s1")).isEmpty();
    }

    private static WebSocketSession session(String id) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
