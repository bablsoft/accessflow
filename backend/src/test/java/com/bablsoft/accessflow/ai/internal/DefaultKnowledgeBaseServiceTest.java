package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiConfigNotFoundException;
import com.bablsoft.accessflow.ai.api.AiConfigRagInvalidException;
import com.bablsoft.accessflow.ai.api.CreateKnowledgeDocumentCommand;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentIngestException;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentNotFoundException;
import com.bablsoft.accessflow.ai.internal.config.RagProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.KnowledgeDocumentEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.KnowledgeDocumentRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import com.bablsoft.accessflow.core.api.RagStoreType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultKnowledgeBaseServiceTest {

    private static final UUID CONFIG_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID DOC_ID = UUID.randomUUID();

    @Mock KnowledgeDocumentRepository documentRepository;
    @Mock AiConfigRepository aiConfigRepository;
    @Mock RagComponentsFactory ragComponentsFactory;
    @Mock PgVectorAvailability pgVectorAvailability;
    @Mock EmbeddingModel embeddingModel;
    @Mock VectorStore vectorStore;

    private final RagProperties properties = new RagProperties(1536, 800, 100);

    @BeforeEach
    void pgVectorAvailableByDefault() {
        lenient().when(pgVectorAvailability.isAvailable()).thenReturn(true);
    }

    private DefaultKnowledgeBaseService service() {
        return new DefaultKnowledgeBaseService(documentRepository, aiConfigRepository,
                ragComponentsFactory, properties, pgVectorAvailability);
    }

    private AiConfigEntity ragConfig(boolean ragEnabled) {
        var entity = new AiConfigEntity();
        entity.setId(CONFIG_ID);
        entity.setOrganizationId(ORG_ID);
        entity.setProvider(AiProviderType.ANTHROPIC);
        entity.setRagEnabled(ragEnabled);
        entity.setRagStoreType(RagStoreType.PGVECTOR);
        entity.setEmbeddingProvider(AiProviderType.OPENAI);
        entity.setEmbeddingModel("text-embedding-3-small");
        return entity;
    }

    private KnowledgeDocumentEntity docEntity() {
        var doc = new KnowledgeDocumentEntity();
        doc.setId(DOC_ID);
        doc.setAiConfigId(CONFIG_ID);
        doc.setOrganizationId(ORG_ID);
        doc.setTitle("Data policy");
        doc.setContent("Never expose PII.");
        doc.setCharCount(17);
        doc.setChunkCount(1);
        doc.setStatus(KnowledgeDocumentEntity.STATUS_INDEXED);
        return doc;
    }

    @Test
    void listMapsDocumentsToViews() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(documentRepository.findAllByAiConfigIdOrderByCreatedAtDesc(CONFIG_ID))
                .thenReturn(List.of(docEntity()));

        var views = service().list(CONFIG_ID, ORG_ID);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).title()).isEqualTo("Data policy");
        assertThat(views.get(0).chunkCount()).isEqualTo(1);
    }

    @Test
    void listThrowsWhenConfigMissing() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().list(CONFIG_ID, ORG_ID))
                .isInstanceOf(AiConfigNotFoundException.class);
    }

    @Test
    void createIngestsChunksAndPersists() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service().create(CONFIG_ID, ORG_ID,
                new CreateKnowledgeDocumentCommand("Policy", "Never expose customer PII columns."));

        assertThat(view.title()).isEqualTo("Policy");
        assertThat(view.status()).isEqualTo(KnowledgeDocumentEntity.STATUS_INDEXED);
        assertThat(view.chunkCount()).isGreaterThanOrEqualTo(1);
        verify(vectorStore).add(any());
    }

    @Test
    void createThrowsWhenRagDisabled() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(false)));

        assertThatThrownBy(() -> service().create(CONFIG_ID, ORG_ID,
                new CreateKnowledgeDocumentCommand("t", "c")))
                .isInstanceOf(AiConfigRagInvalidException.class);
    }

    @Test
    void createRejectsBlankTitle() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));

        assertThatThrownBy(() -> service().create(CONFIG_ID, ORG_ID,
                new CreateKnowledgeDocumentCommand("   ", "content")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsBlankContent() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));

        assertThatThrownBy(() -> service().create(CONFIG_ID, ORG_ID,
                new CreateKnowledgeDocumentCommand("title", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsOversizedContent() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        var huge = "x".repeat(101);

        assertThatThrownBy(() -> service().create(CONFIG_ID, ORG_ID,
                new CreateKnowledgeDocumentCommand("title", huge)))
                .isInstanceOf(AiConfigRagInvalidException.class);
    }

    @Test
    void createWrapsIngestionFailure() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
        org.mockito.Mockito.doThrow(new RuntimeException("embed down")).when(vectorStore).add(any());

        assertThatThrownBy(() -> service().create(CONFIG_ID, ORG_ID,
                new CreateKnowledgeDocumentCommand("title", "content")))
                .isInstanceOf(KnowledgeDocumentIngestException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void deleteRemovesVectorsAndRow() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(documentRepository.findByIdAndAiConfigIdAndOrganizationId(DOC_ID, CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(docEntity()));
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);

        service().delete(DOC_ID, CONFIG_ID, ORG_ID);

        verify(vectorStore).delete(anyString());
        verify(documentRepository).delete(any());
    }

    @Test
    void deleteStillRemovesRowWhenVectorDeleteFails() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(documentRepository.findByIdAndAiConfigIdAndOrganizationId(DOC_ID, CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(docEntity()));
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
        org.mockito.Mockito.doThrow(new RuntimeException("store down"))
                .when(vectorStore).delete(anyString());

        service().delete(DOC_ID, CONFIG_ID, ORG_ID);

        verify(documentRepository).delete(any());
    }

    @Test
    void deleteThrowsWhenDocumentMissing() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(documentRepository.findByIdAndAiConfigIdAndOrganizationId(DOC_ID, CONFIG_ID, ORG_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(DOC_ID, CONFIG_ID, ORG_ID))
                .isInstanceOf(KnowledgeDocumentNotFoundException.class);
    }

    @Test
    void testConnectionReturnsOkWithDimensions() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
        when(embeddingModel.embed(anyString())).thenReturn(new float[1536]);

        var result = service().testConnection(CONFIG_ID, ORG_ID);

        assertThat(result.ok()).isTrue();
        assertThat(result.embeddingDimensions()).isEqualTo(1536);
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void testConnectionReportsDimensionMismatchForPgvector() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(ragComponentsFactory.vectorStore(any(), any())).thenReturn(vectorStore);
        when(embeddingModel.embed(anyString())).thenReturn(new float[768]);

        var result = service().testConnection(CONFIG_ID, ORG_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("768");
    }

    @Test
    void testConnectionReturnsErrorOnFailure() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(ragComponentsFactory.embeddingModel(any())).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("401 unauthorized"));

        var result = service().testConnection(CONFIG_ID, ORG_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("401");
    }

    @Test
    void testConnectionThrowsWhenRagDisabled() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(false)));

        assertThatThrownBy(() -> service().testConnection(CONFIG_ID, ORG_ID))
                .isInstanceOf(AiConfigRagInvalidException.class);
    }

    @Test
    void createThrowsWhenPgVectorUnavailable() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(pgVectorAvailability.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service().create(CONFIG_ID, ORG_ID,
                new CreateKnowledgeDocumentCommand("title", "content")))
                .isInstanceOf(AiConfigRagInvalidException.class)
                .hasMessage("error.ai_config.rag.pgvector_unavailable");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void testConnectionThrowsWhenPgVectorUnavailable() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(pgVectorAvailability.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service().testConnection(CONFIG_ID, ORG_ID))
                .isInstanceOf(AiConfigRagInvalidException.class)
                .hasMessage("error.ai_config.rag.pgvector_unavailable");
    }

    @Test
    void deleteSkipsVectorDeleteWhenPgVectorUnavailable() {
        when(aiConfigRepository.findByIdAndOrganizationId(CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(ragConfig(true)));
        when(documentRepository.findByIdAndAiConfigIdAndOrganizationId(DOC_ID, CONFIG_ID, ORG_ID))
                .thenReturn(Optional.of(docEntity()));
        when(pgVectorAvailability.isAvailable()).thenReturn(false);

        service().delete(DOC_ID, CONFIG_ID, ORG_ID);

        verify(ragComponentsFactory, never()).vectorStore(any(), any());
        verify(documentRepository).delete(any());
    }
}
