package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.api.AppendHelpChatTurnCommand;
import com.bablsoft.accessflow.ai.api.HelpChatCitation;
import com.bablsoft.accessflow.ai.api.HelpChatConversationView;
import com.bablsoft.accessflow.ai.api.HelpChatMessage;
import com.bablsoft.accessflow.ai.api.HelpChatMessageView;
import com.bablsoft.accessflow.ai.api.HelpChatQuestionRequiredException;
import com.bablsoft.accessflow.ai.api.HelpChatRole;
import com.bablsoft.accessflow.ai.api.HelpChatSessionNotFoundException;
import com.bablsoft.accessflow.ai.api.HelpChatSessionService;
import com.bablsoft.accessflow.ai.api.HelpChatSessionView;
import com.bablsoft.accessflow.ai.api.HelpChatTurnView;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpChatMessageEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpChatSessionEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpChatMessageRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpChatSessionRepository;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stores help conversations (AF-904).
 *
 * <p>The one thing worth knowing about this class: it never re-derives citations. What the model
 * emitted was a set of {@code [n]} indices, and the server resolved those against the chunks it had
 * actually retrieved before the answer ever left {@link com.bablsoft.accessflow.ai.api.HelpChatService};
 * this class persists that resolved list verbatim (epic AF-899 decision 6). Re-parsing the stored
 * text later would resolve against whatever the corpus happens to contain then — a different
 * document, or none — and would put a link on the page that nothing verified.
 */
@Service
@RequiredArgsConstructor
public class DefaultHelpChatSessionService implements HelpChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultHelpChatSessionService.class);

    private static final TypeReference<List<HelpChatCitation>> CITATIONS_TYPE = new TypeReference<>() {
    };
    private static final String NO_CITATIONS = "[]";

    private final HelpChatSessionRepository sessionRepository;
    private final HelpChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    @Transactional
    public HelpChatSessionView createSession(UUID organizationId, UUID userId) {
        var now = clock.instant();
        var session = new HelpChatSessionEntity();
        session.setId(UUID.randomUUID());
        session.setOrganizationId(organizationId);
        session.setUserId(userId);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return toView(sessionRepository.save(session));
    }

    /**
     * One page of the user's conversations, newest activity first.
     *
     * <p>The caller's sort is deliberately dropped: {@code findPageByOrganizationIdAndUserId} orders
     * on {@code coalesce(last_message_at, created_at)}, which no client could name, and passing a
     * client-supplied property through to JPA would turn a typo in a query string into a 500.
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<HelpChatSessionView> listSessions(UUID organizationId, UUID userId,
                                                          PageRequest pageRequest) {
        var paging = org.springframework.data.domain.PageRequest.of(pageRequest.page(),
                pageRequest.size());
        var page = sessionRepository.findPageByOrganizationIdAndUserId(organizationId, userId,
                paging);
        return new PageResponse<>(page.getContent().stream()
                .map(DefaultHelpChatSessionService::toView).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /**
     * Both messages and the session's counters in one transaction — a half-stored turn would read as
     * an ignored question. The provider call that produced the answer already happened outside this
     * method, which is the point of splitting the two services: nothing here waits on a model.
     */
    @Override
    @Transactional
    public HelpChatTurnView appendTurn(AppendHelpChatTurnCommand command) {
        var question = command.question() == null ? "" : command.question().strip();
        if (question.isBlank()) {
            throw new HelpChatQuestionRequiredException();
        }
        var session = load(command.organizationId(), command.userId(), command.sessionId());
        var answer = command.answer();
        var now = clock.instant();
        // Derived from the counter rather than from a sequence, so two concurrent appends to one
        // session collide on help_chat_messages_session_sequence_idx (a unique violation) before the
        // @Version check on the session can report an optimistic-lock failure. Either way the loser
        // is retried once by DefaultHelpChatConversationService (AF-905) against a re-read counter,
        // rather than costing the user an answer the provider has already been paid for.
        var nextSequence = session.getMessageCount() + 1;

        var userMessage = message(session, HelpChatRole.USER, question, nextSequence, now);
        var assistantMessage = message(session, HelpChatRole.ASSISTANT,
                answer == null ? "" : answer.content(), nextSequence + 1, now);
        if (answer != null) {
            assistantMessage.setCitations(serializeCitations(answer.citations()));
            assistantMessage.setCorpusVersion(command.corpusVersion());
            assistantMessage.setModel(blankToNull(answer.model()));
            assistantMessage.setPromptTokens(answer.promptTokens());
            assistantMessage.setCompletionTokens(answer.completionTokens());
        }
        assistantMessage.setLatencyMs(command.latencyMs());
        messageRepository.saveAll(List.of(userMessage, assistantMessage));

        if (session.getTitle() == null) {
            session.setTitle(deriveTitle(question));
        }
        session.setMessageCount(session.getMessageCount() + 2);
        session.setLastMessageAt(now);
        session.setUpdatedAt(now);
        var saved = sessionRepository.save(session);
        return new HelpChatTurnView(toView(saved), toView(userMessage), toView(assistantMessage));
    }

    /**
     * Read newest-first with a limit, then reversed — the index is the same either way, and asking the
     * database for the tail is the whole point of not calling {@link #loadConversation} here.
     */
    @Override
    @Transactional(readOnly = true)
    public List<HelpChatMessage> loadRecentHistory(UUID organizationId, UUID userId, UUID sessionId,
                                                   int maxMessages) {
        load(organizationId, userId, sessionId);
        if (maxMessages <= 0) {
            return List.of();
        }
        var newestFirst = messageRepository.findBySessionIdOrderBySequenceNumberDesc(sessionId,
                Limit.of(maxMessages));
        var history = new ArrayList<HelpChatMessage>(newestFirst.size());
        for (var i = newestFirst.size() - 1; i >= 0; i--) {
            var message = newestFirst.get(i);
            history.add(new HelpChatMessage(message.getRole(), message.getContent()));
        }
        return List.copyOf(history);
    }

    @Override
    @Transactional(readOnly = true)
    public HelpChatConversationView loadConversation(UUID organizationId, UUID userId,
                                                     UUID sessionId) {
        var session = load(organizationId, userId, sessionId);
        var messages = messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId).stream()
                .map(this::toView)
                .toList();
        return new HelpChatConversationView(toView(session), messages);
    }

    /**
     * Deletes the row and lets {@code ON DELETE CASCADE} take the messages. The entity is re-read
     * inside this transaction rather than deleted from a caller-held reference: it carries a
     * {@code @Version}, and deleting a stale copy fails the optimistic-lock check.
     */
    @Override
    @Transactional
    public void deleteSession(UUID organizationId, UUID userId, UUID sessionId) {
        sessionRepository.delete(load(organizationId, userId, sessionId));
    }

    private HelpChatSessionEntity load(UUID organizationId, UUID userId, UUID sessionId) {
        return sessionRepository
                .findByIdAndOrganizationIdAndUserId(sessionId, organizationId, userId)
                .orElseThrow(() -> new HelpChatSessionNotFoundException(sessionId));
    }

    private static HelpChatMessageEntity message(HelpChatSessionEntity session, HelpChatRole role,
                                                 String content, int sequenceNumber, Instant now) {
        var message = new HelpChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setSessionId(session.getId());
        message.setOrganizationId(session.getOrganizationId());
        message.setSequenceNumber(sequenceNumber);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(now);
        return message;
    }

    /**
     * The first question, collapsed to one line — a title is a label in a list, and a question typed
     * across three lines would render as three lines of list.
     */
    private static String deriveTitle(String question) {
        var collapsed = question.replaceAll("\\s+", " ").strip();
        if (collapsed.length() <= HelpChatSessionEntity.MAX_TITLE_LENGTH) {
            return collapsed;
        }
        // Back off one char when the cut would land inside a surrogate pair, or the title ends in
        // half an emoji.
        var cut = HelpChatSessionEntity.MAX_TITLE_LENGTH;
        if (Character.isHighSurrogate(collapsed.charAt(cut - 1))) {
            cut--;
        }
        return collapsed.substring(0, cut);
    }

    private String serializeCitations(List<HelpChatCitation> citations) {
        return citations == null || citations.isEmpty()
                ? NO_CITATIONS
                : objectMapper.writeValueAsString(citations);
    }

    /**
     * Stored citations, or none when the column holds something this build cannot read. A transcript
     * that renders without its links is worth more than one that fails to open.
     */
    private List<HelpChatCitation> deserializeCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, CITATIONS_TYPE);
        } catch (JacksonException ex) {
            log.warn("Unreadable help chat citations; rendering the message without them: {}",
                    ex.getMessage());
            return List.of();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static HelpChatSessionView toView(HelpChatSessionEntity session) {
        return new HelpChatSessionView(session.getId(), session.getOrganizationId(),
                session.getUserId(), session.getTitle() == null ? "" : session.getTitle(),
                session.getMessageCount(), session.getLastMessageAt(), session.getCreatedAt());
    }

    private HelpChatMessageView toView(HelpChatMessageEntity message) {
        return new HelpChatMessageView(message.getId(), message.getRole(), message.getContent(),
                deserializeCitations(message.getCitations()), message.getCorpusVersion(),
                message.getModel(), message.getPromptTokens(), message.getCompletionTokens(),
                message.getLatencyMs(), message.getCreatedAt());
    }
}
