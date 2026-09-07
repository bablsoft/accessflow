package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.api.AiConfigNotFoundException;
import com.bablsoft.accessflow.ai.api.HelpAgentConfigInvalidException;
import com.bablsoft.accessflow.ai.api.UpdateHelpAgentConfigCommand;
import com.bablsoft.accessflow.ai.internal.HelpAgentConfigUpdatedEvent;
import com.bablsoft.accessflow.ai.internal.RagComponentsFactory;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import com.bablsoft.accessflow.core.api.PgVectorStatus;
import com.bablsoft.accessflow.core.api.RagStoreType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultHelpAgentConfigServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID AI_CONFIG_ID = UUID.randomUUID();

    @Mock HelpAgentConfigRepository repository;
    @Mock AiConfigRepository aiConfigRepository;
    @Mock RagComponentsFactory ragComponentsFactory;
    @Mock PgVectorAvailability pgVectorAvailability;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MessageSource messageSource;
    @Mock EmbeddingModel embeddingModel;
    @Mock VectorStore vectorStore;

    private DefaultHelpAgentConfigService service;

    @BeforeEach
    void setUp() {
        service = new DefaultHelpAgentConfigService(repository, aiConfigRepository,
                ragComponentsFactory, pgVectorAvailability, eventPublisher, messageSource);
        lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(i -> i.getArgument(0));
    }

    // --- reads -------------------------------------------------------------------------------

    @Test
    void getOrDefaultReturnsDefaultsWhenNoRowExists() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.empty());

        var view = service.getOrDefault(ORG_ID);

        assertThat(view.id()).isNull();
        assertThat(view.organizationId()).isEqualTo(ORG_ID);
        assertThat(view.enabled()).isFalse();
        assertThat(view.retrievalEnabled()).isTrue();
        assertThat(view.topK()).isEqualTo(6);
        assertThat(view.similarityThreshold()).isEqualTo(0.4);
        assertThat(view.maxHistoryTurns()).isEqualTo(8);
        assertThat(view.maxQuestionChars()).isEqualTo(2000);
        assertThat(view.sendUserContext()).isTrue();
        assertThat(view.retentionDays()).isEqualTo(90);
        assertThat(view.perUserRequestsPerMinute()).isEqualTo(6);
        assertThat(view.indexedCorpusVersion()).isNull();
        assertThat(view.createdAt()).isNull();
        assertThat(view.updatedAt()).isNull();
    }

    @Test
    void getOrDefaultMapsStoredRow() {
        var entity = storedRow();
        entity.setIndexedCorpusVersion("c0ac599ef7fc");
        entity.setIndexError("boom");
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entity));

        var view = service.getOrDefault(ORG_ID);

        assertThat(view.id()).isEqualTo(entity.getId());
        assertThat(view.aiConfigId()).isEqualTo(AI_CONFIG_ID);
        assertThat(view.indexedCorpusVersion()).isEqualTo("c0ac599ef7fc");
        assertThat(view.indexError()).isEqualTo("boom");
    }

    // --- writes ------------------------------------------------------------------------------

    @Test
    void updateSeedsARowOnFirstWriteAndLeavesUnsetFieldsAlone() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.empty());

        var view = service.update(ORG_ID, command().retentionDays(30).build());

        assertThat(view.id()).isNotNull();
        assertThat(view.retentionDays()).isEqualTo(30);
        assertThat(view.topK()).isEqualTo(6);
        assertThat(view.enabled()).isFalse();
    }

    @Test
    void updateAppliesSendUserContext() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));

        assertThat(service.update(ORG_ID, command().sendUserContext(false).build())
                .sendUserContext()).isFalse();
    }

    @Test
    void updateClearsTheBindingOnlyWhenAsked() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));

        assertThat(service.update(ORG_ID, command().build()).aiConfigId()).isEqualTo(AI_CONFIG_ID);
    }

    @Test
    void updateClearsTheBindingWithClearFlag() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));

        assertThat(service.update(ORG_ID, command().clearAiConfig().build()).aiConfigId()).isNull();
    }

    @Test
    void updatePublishesUpdatedEventWithBindingChange() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        var other = UUID.randomUUID();
        when(aiConfigRepository.existsByIdAndOrganizationId(other, ORG_ID)).thenReturn(true);

        service.update(ORG_ID, command().aiConfigId(other).build());

        var captor = ArgumentCaptor.forClass(HelpAgentConfigUpdatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo(ORG_ID);
        assertThat(captor.getValue().aiConfigId()).isEqualTo(other);
        assertThat(captor.getValue().bindingChanged()).isTrue();
        assertThat(captor.getValue().enabled()).isFalse();
    }

    @Test
    void updatePublishesUpdatedEventWithoutBindingChange() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));

        service.update(ORG_ID, command().retentionDays(45).build());

        var captor = ArgumentCaptor.forClass(HelpAgentConfigUpdatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().bindingChanged()).isFalse();
        assertThat(captor.getValue().retrievalEnabled()).isTrue();
    }

    @Test
    void updateRejectsABindingFromAnotherOrganizationEvenWhileDisabled() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        var foreign = UUID.randomUUID();
        when(aiConfigRepository.existsByIdAndOrganizationId(foreign, ORG_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.update(ORG_ID, command().aiConfigId(foreign).build()))
                .isInstanceOf(AiConfigNotFoundException.class);
        verify(repository, never()).save(any());
    }

    // --- range validation --------------------------------------------------------------------

    @Test
    void updateRejectsOutOfRangeValues() {
        // A fresh row per lookup: a rejected write must not leave its value on the shared fixture.
        when(repository.findByOrganizationId(ORG_ID)).thenAnswer(i -> Optional.of(storedRow()));

        assertInvalid(command().topK(0).build(), "error.help_agent.top_k_range");
        assertInvalid(command().topK(21).build(), "error.help_agent.top_k_range");
        assertInvalid(command().similarityThreshold(-0.1).build(), "error.help_agent.threshold_range");
        assertInvalid(command().similarityThreshold(1.1).build(), "error.help_agent.threshold_range");
        assertInvalid(command().maxHistoryTurns(0).build(), "error.help_agent.max_history_turns_range");
        assertInvalid(command().maxHistoryTurns(51).build(), "error.help_agent.max_history_turns_range");
        assertInvalid(command().maxQuestionChars(99).build(), "error.help_agent.max_question_chars_range");
        assertInvalid(command().maxQuestionChars(10_001).build(), "error.help_agent.max_question_chars_range");
        assertInvalid(command().retentionDays(0).build(), "error.help_agent.retention_days_range");
        assertInvalid(command().retentionDays(3651).build(), "error.help_agent.retention_days_range");
        assertInvalid(command().perUserRequestsPerMinute(0).build(), "error.help_agent.requests_per_minute_range");
        assertInvalid(command().perUserRequestsPerMinute(121).build(), "error.help_agent.requests_per_minute_range");
        verify(repository, never()).save(any());
    }

    @Test
    void updateAcceptsRangeBoundaries() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));

        var view = service.update(ORG_ID, command().topK(20).similarityThreshold(1.0)
                .maxHistoryTurns(50).maxQuestionChars(10_000).retentionDays(3650)
                .perUserRequestsPerMinute(120).build());

        assertThat(view.topK()).isEqualTo(20);
        assertThat(view.retentionDays()).isEqualTo(3650);
    }

    // --- enable validation -------------------------------------------------------------------

    @Test
    void enableRequiresABoundAiConfig() {
        var row = storedRow();
        row.setAiConfigId(null);
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(row));

        assertInvalid(command().enabled(true).build(), "error.help_agent.ai_config_required");
    }

    @Test
    void enableRejectsAnAiConfigFromAnotherOrganization() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ORG_ID, command().enabled(true).build()))
                .isInstanceOf(AiConfigNotFoundException.class);
    }

    @Test
    void enableWithoutRetrievalSkipsEveryRagCheck() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(aiConfig(false, null, null)));

        var view = service.update(ORG_ID, command().enabled(true).retrievalEnabled(false).build());

        assertThat(view.enabled()).isTrue();
        assertThat(view.retrievalEnabled()).isFalse();
        verify(pgVectorAvailability, never()).status();
    }

    @Test
    void enableWithRetrievalRejectsAConfigWithRagOff() {
        bindConfig(aiConfig(false, RagStoreType.PGVECTOR, AiProviderType.OPENAI));

        assertInvalid(command().enabled(true).build(), "error.help_agent.rag_not_enabled");
    }

    @Test
    void enableWithRetrievalRejectsAConfigWithNoStoreType() {
        bindConfig(aiConfig(true, null, AiProviderType.OPENAI));

        assertInvalid(command().enabled(true).build(), "error.help_agent.rag_not_enabled");
    }

    @Test
    void enableWithRetrievalRejectsAConfigWithNoEmbeddingProvider() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, null));

        assertInvalid(command().enabled(true).build(), "error.help_agent.embedding_provider_required");
    }

    @Test
    void enableWithRetrievalRejectsAnthropicEmbeddings() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.ANTHROPIC));

        assertInvalid(command().enabled(true).build(), "error.help_agent.embedding_provider_invalid");
    }

    @Test
    void enableWithRetrievalOnQdrantSkipsThePgVectorProbe() {
        bindConfig(aiConfig(true, RagStoreType.QDRANT, AiProviderType.OPENAI));

        assertThat(service.update(ORG_ID, command().enabled(true).build()).enabled()).isTrue();
        verify(pgVectorAvailability, never()).status();
    }

    @Test
    void anUnrelatedSaveOnAnAlreadyEnabledAgentDoesNotCallTheEmbeddingProvider() {
        var row = storedRow();
        row.setEnabled(true);
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(row));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI)));
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.AVAILABLE);

        // Changing retention must not fail because the embedding provider is having a blip.
        assertThat(service.update(ORG_ID, command().retentionDays(45).build()).retentionDays())
                .isEqualTo(45);
        verify(ragComponentsFactory, never()).embeddingModel(any());
    }

    @Test
    void rebindingAnEnabledAgentReprobesTheDimension() {
        var row = storedRow();
        row.setEnabled(true);
        var other = UUID.randomUUID();
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(row));
        when(aiConfigRepository.existsByIdAndOrganizationId(other, ORG_ID)).thenReturn(true);
        when(aiConfigRepository.findByIdAndOrganizationId(other, ORG_ID))
                .thenReturn(Optional.of(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI)));
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.AVAILABLE);
        when(ragComponentsFactory.pgvectorDimensions()).thenReturn(1536);
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyString())).thenReturn(new float[768]);

        assertInvalid(command().aiConfigId(other).build(),
                "error.help_agent.pgvector_dimension_mismatch");
    }

    // --- the three pgvector states -----------------------------------------------------------

    @Test
    void enableReportsPgVectorDisabledDistinctly() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.DISABLED);

        assertInvalid(command().enabled(true).build(), "error.help_agent.pgvector_disabled");
    }

    @Test
    void enableReportsMissingPgVectorExtensionDistinctly() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.EXTENSION_MISSING);

        assertInvalid(command().enabled(true).build(), "error.help_agent.pgvector_extension_missing");
    }

    @Test
    void enableReportsPgVectorDimensionMismatchDistinctly() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.AVAILABLE);
        when(ragComponentsFactory.pgvectorDimensions()).thenReturn(1536);
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyString())).thenReturn(new float[768]);

        assertInvalid(command().enabled(true).build(), "error.help_agent.pgvector_dimension_mismatch");
    }

    @Test
    void enableReportsAnUnreachableEmbeddingModel() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.AVAILABLE);
        when(ragComponentsFactory.embeddingModel(any())).thenThrow(new IllegalStateException("down"));

        assertInvalid(command().enabled(true).build(), "error.help_agent.embedding_unreachable");
    }

    @Test
    void enableSucceedsWhenPgVectorIsAvailableAndDimensionsMatch() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.AVAILABLE);
        when(ragComponentsFactory.pgvectorDimensions()).thenReturn(1536);
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyString())).thenReturn(new float[1536]);

        assertThat(service.update(ORG_ID, command().enabled(true).build()).enabled()).isTrue();
    }

    // --- connectivity test --------------------------------------------------------------------

    @Test
    void testConnectionReportsNotConfiguredWithoutARow() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.empty());

        var result = service.testConnection(ORG_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).isEqualTo("help_agent.test.not_configured");
    }

    @Test
    void testConnectionReportsNotConfiguredWithoutABinding() {
        var row = storedRow();
        row.setAiConfigId(null);
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(row));

        assertThat(service.testConnection(ORG_ID).detail()).isEqualTo("help_agent.test.not_configured");
    }

    @Test
    void testConnectionReportsNotConfiguredWhenTheBoundConfigIsGone() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.empty());

        assertThat(service.testConnection(ORG_ID).detail()).isEqualTo("help_agent.test.not_configured");
    }

    @Test
    void testConnectionTreatsRetrievalDisabledAsASupportedState() {
        var row = storedRow();
        row.setRetrievalEnabled(false);
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(row));

        var result = service.testConnection(ORG_ID);

        // Epic decision 9: retrieval off is a steady state, so it must not be painted as a fault.
        assertThat(result.ok()).isTrue();
        assertThat(result.embeddingDimensions()).isNull();
        assertThat(result.detail()).isEqualTo("help_agent.test.retrieval_disabled");
    }

    @Test
    void testConnectionEmbedsOnlyOnceForPgVector() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI)));
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.AVAILABLE);
        when(ragComponentsFactory.pgvectorDimensions()).thenReturn(1536);
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
        when(embeddingModel.embed(anyString())).thenReturn(new float[1536]);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        assertThat(service.testConnection(ORG_ID).ok()).isTrue();
        verify(embeddingModel, times(1)).embed(anyString());
    }

    @Test
    void testConnectionReportsAPgVectorDimensionMismatch() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI)));
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.AVAILABLE);
        when(ragComponentsFactory.pgvectorDimensions()).thenReturn(1536);
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyString())).thenReturn(new float[768]);

        var result = service.testConnection(ORG_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).isEqualTo("error.help_agent.pgvector_dimension_mismatch");
    }

    @Test
    void testConnectionNamesTheExceptionWhenItCarriesNoMessage() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(aiConfig(true, RagStoreType.QDRANT, AiProviderType.OPENAI)));
        when(ragComponentsFactory.embeddingModel(any())).thenThrow(new IllegalStateException());

        assertThat(service.testConnection(ORG_ID).detail()).isEqualTo("IllegalStateException");
    }

    @Test
    void testConnectionSurfacesAValidationFailureAsAMessage() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.ANTHROPIC)));

        var result = service.testConnection(ORG_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).isEqualTo("error.help_agent.embedding_provider_invalid");
    }

    @Test
    void testConnectionEmbedsAndSearches() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(aiConfig(true, RagStoreType.QDRANT, AiProviderType.OPENAI)));
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
        when(embeddingModel.embed(anyString())).thenReturn(new float[1536]);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        var result = service.testConnection(ORG_ID);

        assertThat(result.ok()).isTrue();
        assertThat(result.embeddingDimensions()).isEqualTo(1536);
        assertThat(result.detail()).isEqualTo("help_agent.test.success");
    }

    @Test
    void testConnectionReportsAnUnreachableStore() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(aiConfig(true, RagStoreType.QDRANT, AiProviderType.OPENAI)));
        when(ragComponentsFactory.embeddingModel(any())).thenThrow(new IllegalStateException("no route"));

        var result = service.testConnection(ORG_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).isEqualTo("no route");
        assertThat(result.embeddingDimensions()).isNull();
    }

    @Test
    void requestReindexIsAcceptedAndDoesNothingYet() {
        service.requestReindex(ORG_ID);

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // --- helpers ------------------------------------------------------------------------------

    private void assertInvalid(UpdateHelpAgentConfigCommand command, String messageKey) {
        assertThatThrownBy(() -> service.update(ORG_ID, command))
                .isInstanceOf(HelpAgentConfigInvalidException.class)
                .extracting(e -> ((HelpAgentConfigInvalidException) e).messageKey())
                .isEqualTo(messageKey);
    }

    private void bindConfig(AiConfigEntity config) {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(storedRow()));
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(config));
    }

    private HelpAgentConfigEntity storedRow() {
        var entity = new HelpAgentConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG_ID);
        entity.setAiConfigId(AI_CONFIG_ID);
        return entity;
    }

    private AiConfigEntity aiConfig(boolean ragEnabled, RagStoreType storeType,
                                    AiProviderType embeddingProvider) {
        var config = new AiConfigEntity();
        config.setId(AI_CONFIG_ID);
        config.setOrganizationId(ORG_ID);
        config.setRagEnabled(ragEnabled);
        config.setRagStoreType(storeType);
        config.setEmbeddingProvider(embeddingProvider);
        return config;
    }

    private static CommandBuilder command() {
        return new CommandBuilder();
    }

    /** Keeps the eleven-argument command readable in the ~20 call sites above. */
    private static final class CommandBuilder {
        private Boolean enabled;
        private UUID aiConfigId;
        private boolean clearAiConfig;
        private Boolean retrievalEnabled;
        private Integer topK;
        private Double similarityThreshold;
        private Integer maxHistoryTurns;
        private Integer maxQuestionChars;
        private Boolean sendUserContext;
        private Integer retentionDays;
        private Integer perUserRequestsPerMinute;

        CommandBuilder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        CommandBuilder aiConfigId(UUID value) {
            this.aiConfigId = value;
            return this;
        }

        CommandBuilder clearAiConfig() {
            this.clearAiConfig = true;
            return this;
        }

        CommandBuilder retrievalEnabled(boolean value) {
            this.retrievalEnabled = value;
            return this;
        }

        CommandBuilder topK(int value) {
            this.topK = value;
            return this;
        }

        CommandBuilder similarityThreshold(double value) {
            this.similarityThreshold = value;
            return this;
        }

        CommandBuilder maxHistoryTurns(int value) {
            this.maxHistoryTurns = value;
            return this;
        }

        CommandBuilder maxQuestionChars(int value) {
            this.maxQuestionChars = value;
            return this;
        }

        CommandBuilder sendUserContext(boolean value) {
            this.sendUserContext = value;
            return this;
        }

        CommandBuilder retentionDays(int value) {
            this.retentionDays = value;
            return this;
        }

        CommandBuilder perUserRequestsPerMinute(int value) {
            this.perUserRequestsPerMinute = value;
            return this;
        }

        UpdateHelpAgentConfigCommand build() {
            return new UpdateHelpAgentConfigCommand(enabled, aiConfigId, clearAiConfig,
                    retrievalEnabled, topK, similarityThreshold, maxHistoryTurns, maxQuestionChars,
                    sendUserContext, retentionDays, perUserRequestsPerMinute);
        }
    }
}
