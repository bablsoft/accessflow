package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.api.AppendHelpChatTurnCommand;
import com.bablsoft.accessflow.ai.api.HelpChatAnswer;
import com.bablsoft.accessflow.ai.api.HelpChatCitation;
import com.bablsoft.accessflow.ai.api.HelpChatQuestionRequiredException;
import com.bablsoft.accessflow.ai.api.HelpChatRole;
import com.bablsoft.accessflow.ai.api.HelpChatSessionNotFoundException;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpChatMessageEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpChatSessionEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpChatMessageRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpChatSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultHelpChatSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:15:00Z");

    @Mock HelpChatSessionRepository sessionRepository;
    @Mock HelpChatMessageRepository messageRepository;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private DefaultHelpChatSessionService service() {
        return new DefaultHelpChatSessionService(sessionRepository, messageRepository,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private HelpChatSessionEntity session() {
        var session = new HelpChatSessionEntity();
        session.setId(sessionId);
        session.setOrganizationId(organizationId);
        session.setUserId(userId);
        session.setCreatedAt(NOW.minusSeconds(60));
        session.setUpdatedAt(NOW.minusSeconds(60));
        return session;
    }

    private void sessionExists(HelpChatSessionEntity session) {
        when(sessionRepository.findByIdAndOrganizationIdAndUserId(sessionId, organizationId, userId))
                .thenReturn(Optional.of(session));
        when(sessionRepository.save(any(HelpChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static AppendHelpChatTurnCommand turn(UUID organizationId, UUID userId, UUID sessionId,
                                                  String question, HelpChatAnswer answer) {
        return new AppendHelpChatTurnCommand(organizationId, userId, sessionId, question, answer,
                "c0ac599ef7fc", 812);
    }

    private static HelpChatAnswer answer() {
        return new HelpChatAnswer("Open the review queue [1].",
                List.of(new HelpChatCitation(1, "chunk-7", "Reviewing queries", "Guides",
                        "reviewing", "https://accessflow.io/docs/#reviewing")),
                true, "gpt-4o", 3100, 220);
    }

    @Test
    void createsAnEmptyUntitledSession() {
        when(sessionRepository.save(any(HelpChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var view = service().createSession(organizationId, userId);

        assertThat(view.organizationId()).isEqualTo(organizationId);
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.title()).isEmpty();
        assertThat(view.messageCount()).isZero();
        assertThat(view.lastMessageAt()).isNull();
        assertThat(view.createdAt()).isEqualTo(NOW);
    }

    @Test
    void appendsBothMessagesOfATurnAndAdvancesTheSession() {
        var session = session();
        sessionExists(session);

        var view = service().appendTurn(
                turn(organizationId, userId, sessionId, "  How do I approve a query?  ", answer()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HelpChatMessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(HelpChatMessageEntity::getRole)
                .containsExactly(HelpChatRole.USER, HelpChatRole.ASSISTANT);

        assertThat(view.userMessage().content()).isEqualTo("How do I approve a query?");
        assertThat(view.userMessage().citations()).isEmpty();
        assertThat(view.userMessage().promptTokens()).isNull();
        assertThat(view.assistantMessage().content()).isEqualTo("Open the review queue [1].");
        assertThat(view.assistantMessage().model()).isEqualTo("gpt-4o");
        assertThat(view.assistantMessage().promptTokens()).isEqualTo(3100);
        assertThat(view.assistantMessage().completionTokens()).isEqualTo(220);
        assertThat(view.assistantMessage().latencyMs()).isEqualTo(812);
        assertThat(view.assistantMessage().corpusVersion()).isEqualTo("c0ac599ef7fc");
        assertThat(view.session().messageCount()).isEqualTo(2);
        assertThat(view.session().lastMessageAt()).isEqualTo(NOW);
    }

    /**
     * The citations are stored as the server resolved them and read back as objects, never re-derived
     * from the answer text (epic AF-899 decision 6).
     */
    @Test
    void storesTheResolvedCitationsVerbatim() {
        var session = session();
        sessionExists(session);

        var view = service().appendTurn(
                turn(organizationId, userId, sessionId, "How do I approve a query?", answer()));

        assertThat(view.assistantMessage().citations()).singleElement().satisfies(citation -> {
            assertThat(citation.index()).isEqualTo(1);
            assertThat(citation.chunkId()).isEqualTo("chunk-7");
            assertThat(citation.title()).isEqualTo("Reviewing queries");
            assertThat(citation.url()).isEqualTo("https://accessflow.io/docs/#reviewing");
        });
    }

    @Test
    void titlesTheSessionFromTheFirstQuestionOnly() {
        var session = session();
        sessionExists(session);
        var service = service();

        service.appendTurn(turn(organizationId, userId, sessionId,
                "How do I\n  approve a query?", answer()));
        assertThat(session.getTitle()).isEqualTo("How do I approve a query?");

        service.appendTurn(turn(organizationId, userId, sessionId, "And reject one?", answer()));
        assertThat(session.getTitle()).isEqualTo("How do I approve a query?");
        assertThat(session.getMessageCount()).isEqualTo(4);
    }

    @Test
    void truncatesADerivedTitleToTheColumnWidth() {
        var session = session();
        sessionExists(session);

        service().appendTurn(turn(organizationId, userId, sessionId,
                "q".repeat(HelpChatSessionEntity.MAX_TITLE_LENGTH + 40), answer()));

        assertThat(session.getTitle()).hasSize(HelpChatSessionEntity.MAX_TITLE_LENGTH);
    }

    @Test
    void refusesABlankQuestionBeforeTouchingTheSession() {
        assertThatThrownBy(() -> service().appendTurn(
                turn(organizationId, userId, sessionId, "   ", answer())))
                .isInstanceOf(HelpChatQuestionRequiredException.class);

        verify(sessionRepository, never())
                .findByIdAndOrganizationIdAndUserId(any(), any(), any());
        verify(messageRepository, never()).saveAll(any());
    }

    /** An answer-less turn is still a turn: the question is stored, the reply is empty. */
    @Test
    void storesAnEmptyAssistantMessageWhenThereIsNoAnswer() {
        var session = session();
        sessionExists(session);

        var view = service().appendTurn(
                turn(organizationId, userId, sessionId, "Anyone there?", null));

        assertThat(view.assistantMessage().content()).isEmpty();
        assertThat(view.assistantMessage().citations()).isEmpty();
        assertThat(view.assistantMessage().model()).isNull();
        assertThat(view.assistantMessage().promptTokens()).isNull();
    }

    /**
     * The transcript's order is the sequence number, continuing across turns — both messages of a
     * turn share a {@code created_at}, so nothing else could order them reliably.
     */
    @Test
    void numbersEveryMessageInConversationOrder() {
        sessionExists(session());
        var service = service();

        service.appendTurn(turn(organizationId, userId, sessionId, "First", answer()));
        service.appendTurn(turn(organizationId, userId, sessionId, "Second", answer()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HelpChatMessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository, times(2)).saveAll(captor.capture());
        assertThat(captor.getAllValues().stream()
                .flatMap(List::stream)
                .map(HelpChatMessageEntity::getSequenceNumber))
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void loadsTheConversationInOrder() {
        var session = session();
        session.setTitle("How do I approve a query?");
        session.setMessageCount(2);
        session.setLastMessageAt(NOW);
        when(sessionRepository.findByIdAndOrganizationIdAndUserId(sessionId, organizationId, userId))
                .thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId))
                .thenReturn(List.of(stored(HelpChatRole.USER, "Question", null),
                        stored(HelpChatRole.ASSISTANT, "Answer [1].", "[]")));

        var view = service().loadConversation(organizationId, userId, sessionId);

        assertThat(view.session().title()).isEqualTo("How do I approve a query?");
        assertThat(view.messages()).extracting(m -> m.role())
                .containsExactly(HelpChatRole.USER, HelpChatRole.ASSISTANT);
    }

    /** A citations column this build cannot read costs the links, never the transcript. */
    @Test
    void rendersAMessageWhoseCitationsAreUnreadable() {
        var session = session();
        when(sessionRepository.findByIdAndOrganizationIdAndUserId(sessionId, organizationId, userId))
                .thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId))
                .thenReturn(List.of(stored(HelpChatRole.ASSISTANT, "Answer [1].", "{not json")));

        var view = service().loadConversation(organizationId, userId, sessionId);

        assertThat(view.messages()).singleElement()
                .satisfies(message -> assertThat(message.citations()).isEmpty());
    }

    @Test
    void deletesTheSessionItJustRead() {
        var session = session();
        when(sessionRepository.findByIdAndOrganizationIdAndUserId(sessionId, organizationId, userId))
                .thenReturn(Optional.of(session));

        service().deleteSession(organizationId, userId, sessionId);

        verify(sessionRepository).delete(session);
    }

    /**
     * Another user's session is not found rather than refused — a 403 would confirm the id exists.
     */
    @Test
    void everyReadAndWriteIsScopedToTheOwner() {
        when(sessionRepository.findByIdAndOrganizationIdAndUserId(sessionId, organizationId, userId))
                .thenReturn(Optional.empty());
        var service = service();

        assertThatThrownBy(() -> service.loadConversation(organizationId, userId, sessionId))
                .isInstanceOf(HelpChatSessionNotFoundException.class)
                .satisfies(ex -> assertThat(((HelpChatSessionNotFoundException) ex).sessionId())
                        .isEqualTo(sessionId));
        assertThatThrownBy(() -> service.deleteSession(organizationId, userId, sessionId))
                .isInstanceOf(HelpChatSessionNotFoundException.class);
        assertThatThrownBy(() -> service.appendTurn(
                turn(organizationId, userId, sessionId, "Question", answer())))
                .isInstanceOf(HelpChatSessionNotFoundException.class);
    }

    private HelpChatMessageEntity stored(HelpChatRole role, String content, String citations) {
        var message = new HelpChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setSessionId(sessionId);
        message.setOrganizationId(organizationId);
        message.setRole(role);
        message.setContent(content);
        if (citations != null) {
            message.setCitations(citations);
        }
        message.setCreatedAt(NOW);
        return message;
    }
}
