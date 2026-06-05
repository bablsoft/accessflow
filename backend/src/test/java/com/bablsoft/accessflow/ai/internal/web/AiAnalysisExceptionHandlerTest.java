package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AiConfigRagInvalidException;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentIngestException;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAnalysisExceptionHandlerTest {

    private final MessageSource messageSource = mock(MessageSource.class);
    private final AiAnalysisExceptionHandler handler = new AiAnalysisExceptionHandler(messageSource);

    @Test
    void mapsRagInvalidToBadRequestWithResolvedKey() {
        when(messageSource.getMessage(eq("error.ai_config.rag.store_type_required"), any(), any(Locale.class)))
                .thenReturn("A vector store type is required");

        var pd = handler.handleAiConfigRagInvalid(
                new AiConfigRagInvalidException("error.ai_config.rag.store_type_required"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "RAG_CONFIG_INVALID");
        assertThat(pd.getDetail()).isEqualTo("A vector store type is required");
    }

    @Test
    void mapsKnowledgeDocumentNotFoundToNotFound() {
        when(messageSource.getMessage(eq("error.knowledge_document.not_found"), any(), any(Locale.class)))
                .thenReturn("Knowledge document not found");

        var pd = handler.handleKnowledgeDocumentNotFound(
                new KnowledgeDocumentNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "KNOWLEDGE_DOCUMENT_NOT_FOUND");
    }

    @Test
    void mapsIngestFailureToBadGateway() {
        when(messageSource.getMessage(eq("error.ai_config.rag.ingest_failed"), any(), any(Locale.class)))
                .thenReturn("Failed to embed and store the knowledge document");

        var pd = handler.handleKnowledgeDocumentIngest(
                new KnowledgeDocumentIngestException("boom", new RuntimeException("embed down")));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(pd.getProperties()).containsEntry("error", "RAG_INGEST_FAILED");
    }
}
