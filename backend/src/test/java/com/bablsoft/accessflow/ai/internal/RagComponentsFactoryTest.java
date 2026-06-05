package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.RagProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.RagStoreType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagComponentsFactoryTest {

    @Mock EmbeddingModelFactory embeddingModelFactory;
    @Mock VectorStoreFactory vectorStoreFactory;
    @Mock CredentialEncryptionService encryptionService;
    @Mock EmbeddingModel embeddingModel;
    @Mock VectorStore vectorStore;

    private final RagProperties properties = new RagProperties(1536, 800, 100_000);

    private RagComponentsFactory factory() {
        return new RagComponentsFactory(embeddingModelFactory, vectorStoreFactory, encryptionService,
                properties);
    }

    private AiConfigEntity ragEntity() {
        var entity = new AiConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(UUID.randomUUID());
        entity.setProvider(AiProviderType.ANTHROPIC);
        entity.setRagEnabled(true);
        entity.setRagStoreType(RagStoreType.PGVECTOR);
        entity.setRagTopK(7);
        entity.setRagSimilarityThreshold(0.7);
        entity.setEmbeddingProvider(AiProviderType.OPENAI);
        entity.setEmbeddingModel("text-embedding-3-small");
        return entity;
    }

    @Test
    void pgvectorDimensionsComesFromProperties() {
        assertThat(factory().pgvectorDimensions()).isEqualTo(1536);
    }

    @Test
    void embeddingModelDecryptsKeyAndDelegates() {
        var entity = ragEntity();
        entity.setEmbeddingApiKeyEncrypted("ENC(k)");
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk-embed");
        when(embeddingModelFactory.create(AiProviderType.OPENAI, "sk-embed", "text-embedding-3-small", null))
                .thenReturn(embeddingModel);

        assertThat(factory().embeddingModel(entity)).isSameAs(embeddingModel);
    }

    @Test
    void embeddingModelUsesPlaceholderWhenNoKey() {
        var entity = ragEntity();
        when(embeddingModelFactory.create(eq(AiProviderType.OPENAI), eq("not-needed"), any(), any()))
                .thenReturn(embeddingModel);

        assertThat(factory().embeddingModel(entity)).isSameAs(embeddingModel);
    }

    @Test
    void vectorStorePassesNullKeyWhenAbsent() {
        var entity = ragEntity();
        when(vectorStoreFactory.create(eq(RagStoreType.PGVECTOR), eq(embeddingModel), eq(1536),
                any(), any(), eq(null))).thenReturn(vectorStore);

        assertThat(factory().vectorStore(entity, embeddingModel)).isSameAs(vectorStore);
    }

    @Test
    void retrieverIsDisabledWhenRagOff() {
        var entity = ragEntity();
        entity.setRagEnabled(false);

        assertThat(factory().retriever(entity)).isEqualTo(RagRetriever.DISABLED);
    }

    @Test
    void retrieverIsDisabledWhenStoreTypeNull() {
        var entity = ragEntity();
        entity.setRagStoreType(null);

        assertThat(factory().retriever(entity)).isEqualTo(RagRetriever.DISABLED);
    }

    @Test
    void retrieverIsDisabledWhenEmbeddingProviderNull() {
        var entity = ragEntity();
        entity.setEmbeddingProvider(null);

        assertThat(factory().retriever(entity)).isEqualTo(RagRetriever.DISABLED);
    }

    @Test
    void retrieverIsDisabledWhenBuildFails() {
        var entity = ragEntity();
        when(embeddingModelFactory.create(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        assertThat(factory().retriever(entity)).isEqualTo(RagRetriever.DISABLED);
    }

    @Test
    void retrieverBuildsDefaultRagRetrieverWhenConfigured() {
        var entity = ragEntity();
        when(embeddingModelFactory.create(any(), any(), any(), any())).thenReturn(embeddingModel);
        when(vectorStoreFactory.create(eq(RagStoreType.PGVECTOR), eq(embeddingModel), eq(1536),
                any(), any(), any())).thenReturn(vectorStore);

        var retriever = factory().retriever(entity);

        assertThat(retriever).isInstanceOf(DefaultRagRetriever.class);
        verify(vectorStoreFactory).create(eq(RagStoreType.PGVECTOR), eq(embeddingModel), eq(1536),
                any(), any(), any());
    }
}
