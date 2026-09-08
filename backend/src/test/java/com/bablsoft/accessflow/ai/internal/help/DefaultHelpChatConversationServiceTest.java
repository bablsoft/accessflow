package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.api.AppendHelpChatTurnCommand;
import com.bablsoft.accessflow.ai.api.AskHelpChatCommand;
import com.bablsoft.accessflow.ai.api.HelpChatAnswer;
import com.bablsoft.accessflow.ai.api.HelpChatCitation;
import com.bablsoft.accessflow.ai.api.HelpChatConversationView;
import com.bablsoft.accessflow.ai.api.HelpChatMessageView;
import com.bablsoft.accessflow.ai.api.HelpChatRequest;
import com.bablsoft.accessflow.ai.api.HelpChatRole;
import com.bablsoft.accessflow.ai.api.HelpChatService;
import com.bablsoft.accessflow.ai.api.HelpChatSessionNotFoundException;
import com.bablsoft.accessflow.ai.api.HelpChatSessionService;
import com.bablsoft.accessflow.ai.api.HelpChatSessionView;
import com.bablsoft.accessflow.ai.api.HelpChatTurnView;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultHelpChatConversationServiceTest {

    private static final String CORPUS_VERSION = "c0ac599ef7fc";

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private HelpChatService helpChatService;
    private HelpChatSessionService sessionService;
    private HelpAgentConfigRepository configRepository;
    private HelpQuickReference quickReference;
    private HelpCorpusBundle corpusBundle;
    private DefaultHelpChatConversationService service;

    @BeforeEach
    void setUp() {
        helpChatService = mock(HelpChatService.class);
        sessionService = mock(HelpChatSessionService.class);
        configRepository = mock(HelpAgentConfigRepository.class);
        quickReference = mock(HelpQuickReference.class);
        corpusBundle = mock(HelpCorpusBundle.class);
        when(corpusBundle.available()).thenReturn(true);
        when(corpusBundle.corpusVersion()).thenReturn(CORPUS_VERSION);
        when(corpusBundle.chunkCount()).thenReturn(512);
        service = new DefaultHelpChatConversationService(helpChatService, sessionService,
                configRepository, quickReference, corpusBundle,
                Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC));
    }

    // --- availability -------------------------------------------------------

    @Test
    void reportsDisabledRatherThanFailingWhenNoRowHasBeenSaved() {
        when(configRepository.findByOrganizationId(organizationId)).thenReturn(Optional.empty());

        var availability = service.availability(organizationId);

        assertThat(availability.enabled()).isFalse();
        assertThat(availability.retrievalActive()).isFalse();
        assertThat(availability.corpusVersion()).isEqualTo(CORPUS_VERSION);
        assertThat(availability.chunkCount()).isEqualTo(512);
    }

    /** Enabled but unbound is inert — what deleting the bound {@code ai_config} leaves behind. */
    @Test
    void reportsDisabledForAnEnabledRowWithNoAiConfig() {
        when(configRepository.findByOrganizationId(organizationId))
                .thenReturn(Optional.of(config(true, null)));

        assertThat(service.availability(organizationId).enabled()).isFalse();
    }

    @Test
    void reportsRetrievalInactiveWhenTheIndexIsNotTheRunningCorpus() {
        var config = config(true, UUID.randomUUID());
        when(configRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(config));
        when(quickReference.usable(config)).thenReturn(false);

        var availability = service.availability(organizationId);

        assertThat(availability.enabled()).isTrue();
        assertThat(availability.retrievalActive()).isFalse();
    }

    @Test
    void reportsRetrievalActiveWhenTheIndexMatchesTheBundle() {
        var config = config(true, UUID.randomUUID());
        when(configRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(config));
        when(quickReference.usable(config)).thenReturn(true);

        assertThat(service.availability(organizationId).retrievalActive()).isTrue();
    }

    @Test
    void reportsAnEmptyCorpusVersionWhenTheBundleCouldNotBeLoaded() {
        when(corpusBundle.available()).thenReturn(false);
        when(configRepository.findByOrganizationId(organizationId)).thenReturn(Optional.empty());

        var availability = service.availability(organizationId);

        assertThat(availability.corpusVersion()).isEmpty();
        assertThat(availability.chunkCount()).isZero();
    }

    // --- ask ----------------------------------------------------------------

    @Test
    void answersOutsideTheTranscriptWriteAndStoresTheTurn() {
        givenConversation(List.of(message(HelpChatRole.USER, "First?"),
                message(HelpChatRole.ASSISTANT, "First answer [1]")));
        var answer = new HelpChatAnswer("Second answer [1]",
                List.of(new HelpChatCitation(1, "chunk-1", "Break-glass", "Guides", "break-glass",
                        "https://accessflow.io/docs/#break-glass")),
                true, "gpt-4o", 900, 120);
        when(helpChatService.answer(any())).thenReturn(answer);
        when(sessionService.appendTurn(any())).thenReturn(turn());

        service.ask(new AskHelpChatCommand(organizationId, userId, sessionId, "Second?",
                "Review queue", List.of("QUERY_REVIEW"), "en-GB"));

        // Load, then answer, then append: the provider call must not sit inside the write.
        InOrder order = inOrder(sessionService, helpChatService);
        order.verify(sessionService).loadConversation(organizationId, userId, sessionId);
        order.verify(helpChatService).answer(any());
        order.verify(sessionService).appendTurn(any());

        var request = captureRequest();
        assertThat(request.question()).isEqualTo("Second?");
        assertThat(request.history()).containsExactly(
                new com.bablsoft.accessflow.ai.api.HelpChatMessage(HelpChatRole.USER, "First?"),
                new com.bablsoft.accessflow.ai.api.HelpChatMessage(HelpChatRole.ASSISTANT,
                        "First answer [1]"));
        assertThat(request.routeLabel()).isEqualTo("Review queue");
        assertThat(request.permissions()).containsExactly("QUERY_REVIEW");
        assertThat(request.language()).isEqualTo("en-GB");

        var stored = captureAppend();
        assertThat(stored.answer()).isSameAs(answer);
        assertThat(stored.corpusVersion()).isEqualTo(CORPUS_VERSION);
        assertThat(stored.latencyMs()).isNotNull();
    }

    /** The exfiltration path the epic closes: a route that is really a URL never reaches the model. */
    @Test
    void stripsARouteLabelCarryingAQueryId() {
        givenConversation(List.of());
        when(helpChatService.answer(any())).thenReturn(quickReferenceAnswer());
        when(sessionService.appendTurn(any())).thenReturn(turn());

        service.ask(new AskHelpChatCommand(organizationId, userId, sessionId, "What is this?",
                "/queries/2f1c8a9e-4d3b-4f21-9a77-1b0e5c6d7a88", List.of(), "en"));

        assertThat(captureRequest().routeLabel()).isEmpty();
    }

    /**
     * A quick-reference answer read no indexed section, so stamping a corpus revision on it would make
     * the transcript claim provenance it never had.
     */
    @Test
    void storesNoCorpusVersionForAQuickReferenceAnswer() {
        givenConversation(List.of());
        when(helpChatService.answer(any())).thenReturn(quickReferenceAnswer());
        when(sessionService.appendTurn(any())).thenReturn(turn());

        service.ask(new AskHelpChatCommand(organizationId, userId, sessionId, "How?", null,
                List.of(), "en"));

        assertThat(captureAppend().corpusVersion()).isNull();
    }

    /**
     * The answer has already been paid for by the time the write races, so the append is retried once
     * rather than thrown away.
     */
    @Test
    void retriesTheAppendOnceWhenAConcurrentSendWonTheSequence() {
        givenConversation(List.of());
        when(helpChatService.answer(any())).thenReturn(quickReferenceAnswer());
        when(sessionService.appendTurn(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate sequence_number"))
                .thenReturn(turn());

        var stored = service.ask(new AskHelpChatCommand(organizationId, userId, sessionId, "How?",
                null, List.of(), "en"));

        assertThat(stored).isNotNull();
        verify(sessionService, times(2)).appendTurn(any());
        // One retry, not a loop: a second failure is a real problem and must surface.
        verify(helpChatService).answer(any());
    }

    @Test
    void propagatesASecondConcurrentWriteFailure() {
        givenConversation(List.of());
        when(helpChatService.answer(any())).thenReturn(quickReferenceAnswer());
        when(sessionService.appendTurn(any()))
                .thenThrow(new OptimisticLockingFailureException("stale session"));

        assertThatThrownBy(() -> service.ask(new AskHelpChatCommand(organizationId, userId,
                sessionId, "How?", null, List.of(), "en")))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verify(sessionService, times(2)).appendTurn(any());
    }

    /** A session that is not yours costs no rate-limit budget and no provider call. */
    @Test
    void refusesBeforeAnyProviderCallWhenTheSessionIsNotTheCallers() {
        when(sessionService.loadConversation(organizationId, userId, sessionId))
                .thenThrow(new HelpChatSessionNotFoundException(sessionId));

        assertThatThrownBy(() -> service.ask(new AskHelpChatCommand(organizationId, userId,
                sessionId, "Why?", "Review queue", List.of(), "en")))
                .isInstanceOf(HelpChatSessionNotFoundException.class);

        verifyNoInteractions(helpChatService);
        verify(sessionService, never()).appendTurn(any());
    }

    // --- helpers ------------------------------------------------------------

    private void givenConversation(List<HelpChatMessageView> messages) {
        when(sessionService.loadConversation(organizationId, userId, sessionId))
                .thenReturn(new HelpChatConversationView(session(), messages));
    }

    private HelpChatRequest captureRequest() {
        var captor = ArgumentCaptor.forClass(HelpChatRequest.class);
        verify(helpChatService).answer(captor.capture());
        return captor.getValue();
    }

    private AppendHelpChatTurnCommand captureAppend() {
        var captor = ArgumentCaptor.forClass(AppendHelpChatTurnCommand.class);
        verify(sessionService).appendTurn(captor.capture());
        return captor.getValue();
    }

    private static HelpChatAnswer quickReferenceAnswer() {
        return new HelpChatAnswer("An answer", List.of(), false, "gpt-4o", 100, 20);
    }

    private HelpChatSessionView session() {
        return new HelpChatSessionView(sessionId, organizationId, userId, "First?", 2,
                Instant.parse("2026-09-08T09:00:00Z"), Instant.parse("2026-09-08T08:00:00Z"));
    }

    private HelpChatTurnView turn() {
        return new HelpChatTurnView(session(), message(HelpChatRole.USER, "Second?"),
                message(HelpChatRole.ASSISTANT, "Second answer [1]"));
    }

    private static HelpChatMessageView message(HelpChatRole role, String content) {
        return new HelpChatMessageView(UUID.randomUUID(), role, content, List.of(), null, null,
                null, null, null, Instant.parse("2026-09-08T09:00:00Z"));
    }

    private HelpAgentConfigEntity config(boolean enabled, UUID aiConfigId) {
        var config = new HelpAgentConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(organizationId);
        config.setEnabled(enabled);
        config.setAiConfigId(aiConfigId);
        return config;
    }
}
