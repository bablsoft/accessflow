package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.api.AppendHelpChatTurnCommand;
import com.bablsoft.accessflow.ai.api.AskHelpChatCommand;
import com.bablsoft.accessflow.ai.api.HelpChatAvailabilityView;
import com.bablsoft.accessflow.ai.api.HelpChatConversationService;
import com.bablsoft.accessflow.ai.api.HelpChatMessage;
import com.bablsoft.accessflow.ai.api.HelpChatMessageView;
import com.bablsoft.accessflow.ai.api.HelpChatRequest;
import com.bablsoft.accessflow.ai.api.HelpChatService;
import com.bablsoft.accessflow.ai.api.HelpChatSessionService;
import com.bablsoft.accessflow.ai.api.HelpChatTurnView;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Runs one help question through the two services that own its halves and stores the result
 * (AF-905).
 *
 * <p>The order is the whole point of the class: load the transcript, answer <em>outside</em> any
 * transaction, then append. {@link HelpChatService} takes seconds because it calls a model, and
 * {@link HelpChatSessionService#appendTurn} is a short write — running the second around the first
 * would hold a pooled connection for the length of a provider call.
 *
 * <p>Session lookup happens first, so asking into a conversation that is not yours costs no rate-limit
 * budget and no provider call — it is a 404 before anything else runs.
 */
@Service
@RequiredArgsConstructor
public class DefaultHelpChatConversationService implements HelpChatConversationService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultHelpChatConversationService.class);

    private final HelpChatService helpChatService;
    private final HelpChatSessionService sessionService;
    private final HelpAgentConfigRepository configRepository;
    private final HelpQuickReference quickReference;
    private final HelpCorpusBundle corpusBundle;
    private final Clock clock;

    /**
     * Never throws for an agent that cannot answer — a client asks this precisely so it can hide its
     * launcher instead of discovering the problem through a failed POST.
     */
    @Override
    public HelpChatAvailabilityView availability(UUID organizationId) {
        var config = configRepository.findByOrganizationId(organizationId).orElse(null);
        var enabled = config != null && config.isEnabled() && config.getAiConfigId() != null;
        // Retrieval is "active" on exactly the condition the chat runtime uses to decide whether to
        // search at all: this organization's stored vectors are the ones the running build ships.
        var retrievalActive = enabled && quickReference.usable(config);
        return new HelpChatAvailabilityView(enabled, retrievalActive,
                corpusBundle.available() ? corpusBundle.corpusVersion() : "",
                corpusBundle.available() ? corpusBundle.chunkCount() : 0);
    }

    @Override
    public HelpChatTurnView ask(AskHelpChatCommand command) {
        var conversation = sessionService.loadConversation(command.organizationId(),
                command.userId(), command.sessionId());
        var request = new HelpChatRequest(command.organizationId(), command.userId(),
                command.question(), conversation.messages().stream()
                        .map(DefaultHelpChatConversationService::toHistoryEntry)
                        .toList(),
                HelpRouteLabel.sanitize(command.routeLabel()), command.permissions(),
                command.language());

        var startedAt = clock.millis();
        var answer = helpChatService.answer(request);
        var latencyMs = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, clock.millis() - startedAt));

        // The corpus version is stamped only when the answer actually came from retrieved sections.
        // A quick-reference answer cites nothing, and recording a revision it did not read would make
        // the transcript claim provenance it never had.
        var corpusVersion = answer.retrievalUsed() && corpusBundle.available()
                ? corpusBundle.corpusVersion()
                : null;
        return append(new AppendHelpChatTurnCommand(command.organizationId(), command.userId(),
                command.sessionId(), command.question(), answer, corpusVersion, latencyMs));
    }

    /**
     * Stores the turn, retrying once if an impatient double-send raced us.
     *
     * <p>Two sends into one session both read the same message counter, so the loser fails either on
     * {@code help_chat_messages_session_sequence_idx} or on the session's {@code @Version} check.
     * Failing the request there would throw away an answer the provider has already been paid for and
     * charged to the organization's budget, so the write is retried once against a re-read counter —
     * by which point the winner has committed and the sequence has moved on. A second failure is a
     * real problem and is propagated.
     */
    private HelpChatTurnView append(AppendHelpChatTurnCommand command) {
        try {
            return sessionService.appendTurn(command);
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            log.debug("Retrying a help turn append for session {} after a concurrent write: {}",
                    command.sessionId(), e.getMessage());
            return sessionService.appendTurn(command);
        }
    }

    private static HelpChatMessage toHistoryEntry(HelpChatMessageView message) {
        return new HelpChatMessage(message.role(), message.content());
    }
}
