package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.RagComponentsFactory;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import com.bablsoft.accessflow.core.api.PgVectorStatus;
import com.bablsoft.accessflow.core.api.RagStoreType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HelpCorpusRetrieverFactoryTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID AI_CONFIG_ID = UUID.randomUUID();

    @Mock AiConfigRepository aiConfigRepository;
    @Mock RagComponentsFactory ragComponentsFactory;
    @Mock PgVectorAvailability pgVectorAvailability;
    @Mock EmbeddingModel embeddingModel;
    @Mock VectorStore vectorStore;

    @InjectMocks HelpCorpusRetrieverFactory factory;

    @Test
    void buildsARetrieverTunedByTheHelpRowNotTheAiConfig() {
        var row = row();
        row.setTopK(9);
        row.setSimilarityThreshold(0.15);
        bindRetrievableConfig();

        var retriever = factory.retriever(row);

        assertThat(retriever).isPresent();
        // Proven through behaviour: the request the retriever issues carries the row's tunables.
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(java.util.List.of());
        retriever.get().retrieve("anything");
        var captor = org.mockito.ArgumentCaptor
                .forClass(org.springframework.ai.vectorstore.SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getTopK()).isEqualTo(9);
        assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.15);
    }

    @Test
    void isEmptyForANullConfiguration() {
        assertThat(factory.retriever(null)).isEmpty();
    }

    @Test
    void isEmptyWhenTheAgentIsOff() {
        var row = row();
        row.setEnabled(false);

        assertThat(factory.retriever(row)).isEmpty();
        verify(aiConfigRepository, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void isEmptyWhenRetrievalIsOff() {
        var row = row();
        row.setRetrievalEnabled(false);

        // A supported steady state: the caller substitutes the quick-reference block.
        assertThat(factory.retriever(row)).isEmpty();
    }

    @Test
    void isEmptyWhenTheBindingWasCleared() {
        var row = row();
        row.setAiConfigId(null);

        assertThat(factory.retriever(row)).isEmpty();
    }

    @Test
    void isEmptyWhenTheBoundConfigurationWasDeleted() {
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.empty());

        assertThat(factory.retriever(row())).isEmpty();
    }

    @Test
    void isEmptyWhenTheBoundConfigurationCannotEmbed() {
        bindConfig(aiConfig(true, RagStoreType.QDRANT, AiProviderType.ANTHROPIC));

        assertThat(factory.retriever(row())).isEmpty();
    }

    @Test
    void isEmptyWhenRagWasTurnedOffOnTheBoundConfiguration() {
        bindConfig(aiConfig(false, RagStoreType.PGVECTOR, AiProviderType.OPENAI));

        assertThat(factory.retriever(row())).isEmpty();
    }

    @Test
    void isEmptyWhenPgVectorIsUnavailableOnThisDeployment() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.isAvailable()).thenReturn(false);
        when(pgVectorAvailability.status()).thenReturn(PgVectorStatus.DISABLED);

        assertThat(factory.retriever(row())).isEmpty();
    }

    @Test
    void isEmptyWhenTheComponentsCannotBeBuilt() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.isAvailable()).thenReturn(true);
        when(ragComponentsFactory.embeddingModel(any()))
                .thenThrow(new IllegalArgumentException("bad embedding endpoint"));

        assertThat(factory.retriever(row())).isEmpty();
    }

    private void bindConfig(AiConfigEntity config) {
        when(aiConfigRepository.findByIdAndOrganizationId(AI_CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(config));
    }

    private void bindRetrievableConfig() {
        bindConfig(aiConfig(true, RagStoreType.PGVECTOR, AiProviderType.OPENAI));
        when(pgVectorAvailability.isAvailable()).thenReturn(true);
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
    }

    private static HelpAgentConfigEntity row() {
        var entity = new HelpAgentConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG_ID);
        entity.setAiConfigId(AI_CONFIG_ID);
        entity.setEnabled(true);
        return entity;
    }

    private static AiConfigEntity aiConfig(boolean ragEnabled, RagStoreType storeType,
                                           AiProviderType embeddingProvider) {
        var config = new AiConfigEntity();
        config.setId(AI_CONFIG_ID);
        config.setOrganizationId(ORG_ID);
        config.setRagEnabled(ragEnabled);
        config.setRagStoreType(storeType);
        config.setEmbeddingProvider(embeddingProvider);
        return config;
    }
}
