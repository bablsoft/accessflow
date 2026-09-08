package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.api.AiRateLimitExceededException;
import com.bablsoft.accessflow.ai.api.HelpChatMessage;
import com.bablsoft.accessflow.ai.api.HelpChatQuestionRequiredException;
import com.bablsoft.accessflow.ai.api.HelpChatRequest;
import com.bablsoft.accessflow.ai.api.HelpChatRole;
import com.bablsoft.accessflow.ai.api.HelpChatUnavailableException;
import com.bablsoft.accessflow.ai.internal.AiAnalyzerStrategyHolder;
import com.bablsoft.accessflow.ai.internal.AiRateLimiter;
import com.bablsoft.accessflow.ai.internal.ChatModelInvoker;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The chat runtime, with the provider call standing in for a model. The assertions that matter here
 * are the ones a real provider could never be trusted to satisfy: that a citation index the model
 * invented resolves to nothing, that a URL it wrote is never turned into a link, and that a turn
 * which fails still costs the user their place in the rate-limit window.
 */
@ExtendWith(MockitoExtension.class)
class DefaultHelpChatServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AI_CONFIG_ID = UUID.randomUUID();

    @Mock HelpAgentConfigRepository configRepository;
    @Mock HelpCorpusRetrieverFactory retrieverFactory;
    @Mock HelpQuickReference quickReference;
    @Mock AiRateLimiter aiRateLimiter;
    @Mock HelpChatRateLimiter helpChatRateLimiter;
    @Mock HelpCorpusRetriever retriever;
    @Mock AiAnalyzerStrategyHolder strategyHolder;
    @Mock ObjectProvider<AiAnalyzerStrategyHolder> strategyHolderProvider;

    private DefaultHelpChatService service;
    private HelpAgentConfigEntity config;

    @BeforeEach
    void setUp() {
        service = new DefaultHelpChatService(configRepository, retrieverFactory, quickReference,
                new HelpChatPromptRenderer(), aiRateLimiter, helpChatRateLimiter,
                strategyHolderProvider);
        config = config(c -> { });
        // Shared happy path: every test overrides part of it, so these are lenient individually
        // rather than the whole class being lenient — a stub that stops matching should still fail.
        lenient().when(strategyHolderProvider.getObject()).thenReturn(strategyHolder);
        lenient().when(configRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(config));
        lenient().when(quickReference.usable(any())).thenReturn(true);
        lenient().when(quickReference.text()).thenReturn("Orientation block");
        lenient().when(retrieverFactory.retriever(any())).thenReturn(Optional.of(retriever));
        lenient().when(retriever.retrieve(anyString())).thenReturn(chunks(6));
        answers("See [1] and [3].");
    }

    @Test
    void resolvesCitationsFromTheRetrievedSet() {
        var answer = service.answer(request("how do I approve a query?"));

        assertThat(answer.retrievalUsed()).isTrue();
        assertThat(answer.citations()).extracting("index", "chunkId", "title", "url")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "chunk-1", "Title 1",
                                "https://accessflow.io/docs/#s1"),
                        org.assertj.core.groups.Tuple.tuple(3, "chunk-3", "Title 3",
                                "https://accessflow.io/docs/#s3"));
    }

    @Test
    void dropsACitationIndexTheModelInvented() {
        answers("Do this [2], and also [9].");

        var answer = service.answer(request("q"));

        assertThat(answer.citations()).extracting("index").containsExactly(2);
        assertThat(answer.content()).contains("[9]");
    }

    @Test
    void groupedIndicesAreResolvedIndividuallyAndDeduplicated() {
        answers("Both [2, 4] apply, and [2] again.");

        var answer = service.answer(request("q"));

        assertThat(answer.citations()).extracting("index").containsExactly(2, 4);
    }

    /**
     * The model is told never to write a URL; if it does anyway, the URL stays inert text. Nothing
     * on this path reads a link out of the answer, so a jailbroken model cannot make the help panel
     * render one (epic AF-899 decision 6).
     */
    @Test
    void aUrlWrittenByTheModelNeverBecomesACitationLink() {
        answers("Go to https://evil.test/login and sign in.");

        var answer = service.answer(request("q"));

        assertThat(answer.citations()).isEmpty();
        assertThat(answer.content()).isEqualTo("Go to https://evil.test/login and sign in.");
    }

    @Test
    void substitutesQuickReferenceWhenTheIndexIsNotUsable() {
        when(quickReference.usable(config)).thenReturn(false);

        var answer = service.answer(request("q"));

        assertThat(answer.retrievalUsed()).isFalse();
        assertThat(answer.citations()).isEmpty();
        assertThat(preamble()).contains("Orientation summary:").contains("Orientation block");
        verifyNoInteractions(retrieverFactory);
    }

    @Test
    void substitutesQuickReferenceWhenNoRetrieverCanBeBuilt() {
        when(retrieverFactory.retriever(config)).thenReturn(Optional.empty());

        var answer = service.answer(request("q"));

        assertThat(answer.retrievalUsed()).isFalse();
        assertThat(preamble()).contains("Orientation block");
    }

    @Test
    void substitutesQuickReferenceWhenRetrievalMatchesNothing() {
        when(retriever.retrieve(anyString())).thenReturn(List.of());

        var answer = service.answer(request("q"));

        assertThat(answer.retrievalUsed()).isFalse();
        assertThat(preamble()).contains("Orientation block");
    }

    @Test
    void truncatesTheQuestionToTheConfiguredLength() {
        config.setMaxQuestionChars(50);

        service.answer(request("x".repeat(500)));

        assertThat(conversation().getLast().getText()).hasSize(50);
        verify(retriever).retrieve(eq("x".repeat(50)));
    }

    @Test
    void capsHistoryAtTheConfiguredNumberOfTurns() {
        config.setMaxHistoryTurns(1);
        var history = new ArrayList<HelpChatMessage>();
        for (int i = 1; i <= 3; i++) {
            history.add(new HelpChatMessage(HelpChatRole.USER, "q" + i));
            history.add(new HelpChatMessage(HelpChatRole.ASSISTANT, "a" + i));
        }

        service.answer(new HelpChatRequest(ORG_ID, USER_ID, "now", history, null, List.of(), "en"));

        assertThat(conversation()).extracting(Message::getText).containsExactly("q3", "a3", "now");
    }

    /**
     * Both counters increment before the model is called, so a turn that fails still counts. Without
     * that, a client retrying on every error would never be limited at all.
     */
    @Test
    void aFailedTurnStillCountsAgainstBothWindows() {
        when(strategyHolder.chatFor(any(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> service.answer(request("q")))
                .isInstanceOf(IllegalStateException.class);

        verify(aiRateLimiter).enforce(ORG_ID);
        verify(helpChatRateLimiter).enforce(ORG_ID, USER_ID, config.getPerUserRequestsPerMinute());
    }

    @Test
    void enforcesTheOrganizationLimitBeforeThePerUserOneAndCallsNoModelWhenLimited() {
        var order = inOrder(aiRateLimiter, helpChatRateLimiter);
        org.mockito.Mockito.doThrow(new AiRateLimitExceededException(6, 60L))
                .when(helpChatRateLimiter).enforce(any(), any(), anyInt());

        assertThatThrownBy(() -> service.answer(request("q")))
                .isInstanceOf(AiRateLimitExceededException.class);

        order.verify(aiRateLimiter).enforce(ORG_ID);
        order.verify(helpChatRateLimiter).enforce(ORG_ID, USER_ID, 6);
        verify(strategyHolder, never()).chatFor(any(), any(), anyString(), any());
    }

    @Test
    void passesThroughTheProviderModelAndTokenCounts() {
        var answer = service.answer(request("q"));

        assertThat(answer.model()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(answer.promptTokens()).isEqualTo(120);
        assertThat(answer.completionTokens()).isEqualTo(40);
        assertThat(answer.totalTokens()).isEqualTo(160);
    }

    @Test
    void answersWithTheBoundConfigurationNotTheOrganizationsFirstUsableOne() {
        service.answer(request("q"));

        verify(strategyHolder).chatFor(eq(ORG_ID), eq(AI_CONFIG_ID), anyString(), any());
    }

    @Test
    void refusesWhenNoConfigurationHasBeenSaved() {
        when(configRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.answer(request("q")))
                .isInstanceOfSatisfying(HelpChatUnavailableException.class,
                        ex -> assertThat(ex.messageKey()).isEqualTo("error.help_chat.disabled"));
        verifyNoInteractions(aiRateLimiter, helpChatRateLimiter);
    }

    @Test
    void refusesWhenTheAgentIsSwitchedOff() {
        config.setEnabled(false);

        assertThatThrownBy(() -> service.answer(request("q")))
                .isInstanceOfSatisfying(HelpChatUnavailableException.class,
                        ex -> assertThat(ex.messageKey()).isEqualTo("error.help_chat.disabled"));
    }

    /** The inert state deleting the bound {@code ai_config} leaves behind: enabled, but unanswerable. */
    @Test
    void refusesWhenTheBoundConfigurationWasDeleted() {
        config.setAiConfigId(null);

        assertThatThrownBy(() -> service.answer(request("q")))
                .isInstanceOfSatisfying(HelpChatUnavailableException.class,
                        ex -> assertThat(ex.messageKey()).isEqualTo("error.help_chat.unbound"));
    }

    @Test
    void refusesABlankQuestionWithoutSpendingAnything() {
        assertThatThrownBy(() -> service.answer(request("   ")))
                .isInstanceOf(HelpChatQuestionRequiredException.class);
        verifyNoInteractions(aiRateLimiter, helpChatRateLimiter);
    }

    /**
     * The citation URL is the one value the client is allowed to turn into a link, and it reaches here
     * from vector-store metadata. A corpus entry that is not an https documentation link loses the
     * link but keeps the section, which a reader can still find by name.
     */
    @Test
    void aCitationWhoseCorpusUrlIsNotAnHttpsLinkKeepsItsTitleAndLosesItsUrl() {
        when(retriever.retrieve(anyString())).thenReturn(List.of(new RetrievedChunk("chunk-1",
                "Title 1", "Guides", "s1", "javascript:alert(1)", "Body 1", 0.9)));
        answers("As described in [1].");

        var answer = service.answer(request("q"));

        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.url()).isEmpty();
            assertThat(citation.title()).isEqualTo("Title 1");
        });
    }

    @Test
    void citationsFollowFirstAppearanceInTheAnswerNotAscendingIndex() {
        answers("Start with [4], then [2].");

        var answer = service.answer(request("q"));

        assertThat(answer.citations()).extracting("index").containsExactly(4, 2);
    }

    @Test
    void aNullHistoryOrPermissionListIsTreatedAsEmpty() {
        var request = new HelpChatRequest(ORG_ID, USER_ID, "q", null, null, null, null);

        var answer = service.answer(request);

        assertThat(answer.citations()).isNotEmpty();
        assertThat(conversation()).extracting(Message::getText).containsExactly("q");
    }

    private void answers(String text) {
        lenient().when(strategyHolder.chatFor(any(), any(), anyString(), any())).thenReturn(
                new ChatModelInvoker.Invocation(text, "claude-sonnet-4-20250514", 120, 40));
    }

    private String preamble() {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(strategyHolder).chatFor(any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<Message> conversation() {
        var captor = ArgumentCaptor.forClass(List.class);
        verify(strategyHolder).chatFor(any(), any(), anyString(), captor.capture());
        return captor.getValue();
    }

    private static HelpChatRequest request(String question) {
        return new HelpChatRequest(ORG_ID, USER_ID, question, List.of(), "Review queue",
                List.of("QUERY_REVIEW"), "en");
    }

    private static List<RetrievedChunk> chunks(int count) {
        var chunks = new ArrayList<RetrievedChunk>(count);
        for (int i = 1; i <= count; i++) {
            chunks.add(new RetrievedChunk("chunk-" + i, "Title " + i, "Guides", "s" + i,
                    "https://accessflow.io/docs/#s" + i, "Body " + i, 0.9 - i * 0.01));
        }
        return chunks;
    }

    private static HelpAgentConfigEntity config(Consumer<HelpAgentConfigEntity> customizer) {
        var config = new HelpAgentConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(ORG_ID);
        config.setEnabled(true);
        config.setAiConfigId(AI_CONFIG_ID);
        customizer.accept(config);
        return config;
    }
}
