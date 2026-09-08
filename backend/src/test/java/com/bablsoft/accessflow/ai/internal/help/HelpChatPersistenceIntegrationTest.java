package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.api.AppendHelpChatTurnCommand;
import com.bablsoft.accessflow.ai.api.HelpChatAnswer;
import com.bablsoft.accessflow.ai.api.HelpChatCitation;
import com.bablsoft.accessflow.ai.api.HelpChatRole;
import com.bablsoft.accessflow.ai.api.HelpChatSessionNotFoundException;
import com.bablsoft.accessflow.ai.api.HelpChatSessionService;
import com.bablsoft.accessflow.ai.api.HelpChatSessionView;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpChatSessionEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpChatMessageRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpChatSessionRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What only a real Postgres proves about help transcripts (AF-904): that a two-turn conversation
 * reloads in the order it happened, that the {@code citations} jsonb round-trips as objects rather
 * than as text, and that deleting a session — by the service, or in bulk by the retention job —
 * takes its messages with it through the FK cascade.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class HelpChatPersistenceIntegrationTest {

    @Autowired HelpChatSessionService sessionService;
    @Autowired HelpChatSessionRepository sessionRepository;
    @Autowired HelpChatMessageRepository messageRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;

    private UUID organizationId;
    private UUID userId;
    private UUID otherUserId;

    @BeforeEach
    void setUp() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Help Chat Org " + UUID.randomUUID());
        org.setSlug("help-chat-" + UUID.randomUUID());
        organizationRepository.save(org);
        organizationId = org.getId();

        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("help-chat-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Help Chat User");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        userRepository.save(user);
        userId = user.getId();

        var otherUser = new UserEntity();
        otherUser.setId(UUID.randomUUID());
        otherUser.setEmail("help-chat-other-" + UUID.randomUUID() + "@example.com");
        otherUser.setDisplayName("Help Chat Other User");
        otherUser.setPasswordHash("hash");
        otherUser.setRole(UserRoleType.ANALYST);
        otherUser.setAuthProvider(AuthProviderType.LOCAL);
        otherUser.setActive(true);
        otherUser.setOrganization(org);
        userRepository.save(otherUser);
        otherUserId = otherUser.getId();
    }

    private static HelpChatAnswer answer(String content, List<HelpChatCitation> citations) {
        return new HelpChatAnswer(content, citations, !citations.isEmpty(), "gpt-4o", 3100, 220);
    }

    private static HelpChatCitation citation(int index) {
        return new HelpChatCitation(index, "chunk-" + index, "Reviewing queries", "Guides",
                "reviewing", "https://accessflow.io/docs/#reviewing");
    }

    @Test
    void storesTwoTurnsAndReloadsThemInOrderWithTheirCitations() {
        var session = sessionService.createSession(organizationId, userId);

        sessionService.appendTurn(new AppendHelpChatTurnCommand(organizationId, userId,
                session.id(), "How do I approve a query?",
                answer("Open the review queue [1].", List.of(citation(1))), "c0ac599ef7fc", 812));
        sessionService.appendTurn(new AppendHelpChatTurnCommand(organizationId, userId,
                session.id(), "And how do I reject one?",
                answer("Use the reject button [1][2].", List.of(citation(1), citation(2))),
                "c0ac599ef7fc", 640));

        var conversation = sessionService.loadConversation(organizationId, userId, session.id());

        assertThat(conversation.session().title()).isEqualTo("How do I approve a query?");
        assertThat(conversation.session().messageCount()).isEqualTo(4);
        assertThat(conversation.session().lastMessageAt()).isNotNull();
        assertThat(conversation.messages()).extracting(m -> m.role())
                .containsExactly(HelpChatRole.USER, HelpChatRole.ASSISTANT,
                        HelpChatRole.USER, HelpChatRole.ASSISTANT);
        assertThat(conversation.messages()).extracting(m -> m.content())
                .containsExactly("How do I approve a query?", "Open the review queue [1].",
                        "And how do I reject one?", "Use the reject button [1][2].");

        // The citations survive as the objects the server resolved, not as re-parsed answer text.
        var firstAnswer = conversation.messages().get(1);
        assertThat(firstAnswer.corpusVersion()).isEqualTo("c0ac599ef7fc");
        assertThat(firstAnswer.model()).isEqualTo("gpt-4o");
        assertThat(firstAnswer.promptTokens()).isEqualTo(3100);
        assertThat(firstAnswer.completionTokens()).isEqualTo(220);
        assertThat(firstAnswer.latencyMs()).isEqualTo(812);
        assertThat(firstAnswer.citations()).singleElement().satisfies(c -> {
            assertThat(c.index()).isEqualTo(1);
            assertThat(c.chunkId()).isEqualTo("chunk-1");
            assertThat(c.title()).isEqualTo("Reviewing queries");
            assertThat(c.section()).isEqualTo("Guides");
            assertThat(c.anchor()).isEqualTo("reviewing");
            assertThat(c.url()).isEqualTo("https://accessflow.io/docs/#reviewing");
        });
        assertThat(conversation.messages().get(3).citations()).hasSize(2);

        // A question costs nothing and reports nothing.
        var firstQuestion = conversation.messages().getFirst();
        assertThat(firstQuestion.citations()).isEmpty();
        assertThat(firstQuestion.model()).isNull();
        assertThat(firstQuestion.promptTokens()).isNull();
        assertThat(firstQuestion.corpusVersion()).isNull();
    }

    @Test
    void deletingASessionCascadesToItsMessages() {
        var session = sessionService.createSession(organizationId, userId);
        sessionService.appendTurn(new AppendHelpChatTurnCommand(organizationId, userId,
                session.id(), "How do I approve a query?",
                answer("Open the review queue [1].", List.of(citation(1))), "c0ac599ef7fc", 812));
        assertThat(messageRepository.findBySessionIdOrderBySequenceNumberAsc(session.id())).hasSize(2);

        sessionService.deleteSession(organizationId, userId, session.id());

        assertThat(sessionRepository.findById(session.id())).isEmpty();
        assertThat(messageRepository.findBySessionIdOrderBySequenceNumberAsc(session.id())).isEmpty();
    }

    /**
     * The retention job's statement, against the real cascade — nothing is loaded, so the messages
     * can only go with the session because Postgres takes them.
     */
    @Test
    void theRetentionDeleteRemovesStaleSessionsAndTheirMessages() {
        var stale = sessionService.createSession(organizationId, userId);
        sessionService.appendTurn(new AppendHelpChatTurnCommand(organizationId, userId, stale.id(),
                "An old question", answer("An old answer.", List.of()), null, 100));
        var fresh = sessionService.createSession(organizationId, userId);
        sessionService.appendTurn(new AppendHelpChatTurnCommand(organizationId, userId, fresh.id(),
                "A recent question", answer("A recent answer.", List.of()), null, 100));

        var staleRow = sessionRepository.findById(stale.id()).orElseThrow();
        staleRow.setLastMessageAt(Instant.now().minusSeconds(200L * 24 * 3600));
        sessionRepository.saveAndFlush(staleRow);

        var deleted = sessionRepository.deleteByOrganizationIdAndLastActivityBefore(
                organizationId, Instant.now().minusSeconds(90L * 24 * 3600));

        assertThat(deleted).isEqualTo(1);
        assertThat(sessionRepository.findById(stale.id())).isEmpty();
        assertThat(messageRepository.findBySessionIdOrderBySequenceNumberAsc(stale.id())).isEmpty();
        assertThat(sessionRepository.findById(fresh.id())).isPresent();
        assertThat(messageRepository.findBySessionIdOrderBySequenceNumberAsc(fresh.id())).hasSize(2);
    }

    /**
     * A never-used session ages out on {@code created_at}, or an abandoned empty conversation would
     * live forever. Built through the repository because {@code created_at} is {@code updatable =
     * false} — the only way to have an old one is to insert it old.
     */
    @Test
    void theRetentionDeleteFallsBackToCreatedAtForANeverUsedSession() {
        var abandoned = new HelpChatSessionEntity();
        abandoned.setId(UUID.randomUUID());
        abandoned.setOrganizationId(organizationId);
        abandoned.setUserId(userId);
        abandoned.setCreatedAt(Instant.now().minusSeconds(200L * 24 * 3600));
        abandoned.setUpdatedAt(abandoned.getCreatedAt());
        sessionRepository.saveAndFlush(abandoned);

        var deleted = sessionRepository.deleteByOrganizationIdAndLastActivityBefore(
                organizationId, Instant.now().minusSeconds(90L * 24 * 3600));

        assertThat(deleted).isEqualTo(1);
        assertThat(sessionRepository.findById(abandoned.getId())).isEmpty();
    }

    /** A transcript is private to the person who had it — for everyone, admins included. */
    /**
     * Ordering is {@code coalesce(last_message_at, created_at) DESC}, so a session someone opened and
     * never used still sorts by when it was opened rather than falling to the bottom on a NULL.
     */
    @Test
    void listsOnlyTheCallersSessionsNewestActivityFirst() {
        var older = sessionService.createSession(organizationId, userId);
        sessionService.appendTurn(new AppendHelpChatTurnCommand(organizationId, userId, older.id(),
                "An older question", answer("An older answer", List.of()), null, 100));
        var newer = sessionService.createSession(organizationId, userId);
        sessionService.appendTurn(new AppendHelpChatTurnCommand(organizationId, userId, newer.id(),
                "A newer question", answer("A newer answer", List.of()), null, 100));
        var otherUsersSession = sessionService.createSession(organizationId, otherUserId);

        var page = sessionService.listSessions(organizationId, userId, PageRequest.of(0, 20));

        assertThat(page.totalElements()).isEqualTo(2L);
        assertThat(page.content()).extracting(HelpChatSessionView::id)
                .containsExactly(newer.id(), older.id())
                .doesNotContain(otherUsersSession.id());
        assertThat(page.content().getFirst().title()).isEqualTo("A newer question");
    }

    @Test
    void paginatesTheCallersSessions() {
        var first = sessionService.createSession(organizationId, userId);
        sessionService.appendTurn(new AppendHelpChatTurnCommand(organizationId, userId, first.id(),
                "First", answer("First answer", List.of()), null, 100));
        var second = sessionService.createSession(organizationId, userId);
        sessionService.appendTurn(new AppendHelpChatTurnCommand(organizationId, userId, second.id(),
                "Second", answer("Second answer", List.of()), null, 100));

        var page = sessionService.listSessions(organizationId, userId, PageRequest.of(1, 1));

        assertThat(page.totalElements()).isEqualTo(2L);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).extracting(HelpChatSessionView::id).containsExactly(first.id());
    }

    @Test
    void anotherUsersSessionIsNotFound() {
        var session = sessionService.createSession(organizationId, userId);

        assertThatThrownBy(
                () -> sessionService.loadConversation(organizationId, otherUserId, session.id()))
                .isInstanceOf(HelpChatSessionNotFoundException.class);
    }
}
