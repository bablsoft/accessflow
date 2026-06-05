package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.RagProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

/**
 * Builds the per-{@code ai_config} RAG components — the {@link EmbeddingModel}, the
 * {@link VectorStore} and the {@link RagRetriever} — from the row's RAG / embedding settings
 * (AF-336). Shared by {@link AiAnalyzerStrategyHolder} (which injects the retriever into delegates)
 * and {@code DefaultKnowledgeBaseService} (ingestion / deletion / connectivity test) so the decrypt
 * + factory wiring lives in one place.
 */
@Component
@RequiredArgsConstructor
class RagComponentsFactory {

    private static final Logger log = LoggerFactory.getLogger(RagComponentsFactory.class);
    // Embedding clients (OpenAI Java SDK) require a non-blank key to construct even for keyless
    // self-hosted backends (Ollama / local OpenAI-compatible) where it is never sent.
    private static final String PLACEHOLDER_API_KEY = "not-needed";

    private final EmbeddingModelFactory embeddingModelFactory;
    private final VectorStoreFactory vectorStoreFactory;
    private final CredentialEncryptionService encryptionService;
    private final RagProperties ragProperties;

    int pgvectorDimensions() {
        return ragProperties.pgvectorDimensions();
    }

    EmbeddingModel embeddingModel(AiConfigEntity entity) {
        var apiKey = decryptOrPlaceholder(entity.getEmbeddingApiKeyEncrypted());
        return embeddingModelFactory.create(entity.getEmbeddingProvider(), apiKey,
                entity.getEmbeddingModel(), entity.getEmbeddingEndpoint());
    }

    VectorStore vectorStore(AiConfigEntity entity, EmbeddingModel embeddingModel) {
        var ragApiKey = decryptOrNull(entity.getRagApiKeyEncrypted());
        return vectorStoreFactory.create(entity.getRagStoreType(), embeddingModel,
                ragProperties.pgvectorDimensions(), entity.getRagEndpoint(),
                entity.getRagCollection(), ragApiKey);
    }

    /**
     * The retriever for this config, or {@link RagRetriever#DISABLED} when RAG is off / incompletely
     * configured / the components fail to build — retrieval must never break analysis.
     */
    RagRetriever retriever(AiConfigEntity entity) {
        if (!entity.isRagEnabled() || entity.getRagStoreType() == null
                || entity.getEmbeddingProvider() == null) {
            return RagRetriever.DISABLED;
        }
        try {
            var embeddingModel = embeddingModel(entity);
            var vectorStore = vectorStore(entity, embeddingModel);
            return new DefaultRagRetriever(vectorStore, entity.getId(), entity.getRagTopK(),
                    entity.getRagSimilarityThreshold());
        } catch (RuntimeException e) {
            log.warn("Failed to build RAG retriever for ai_config={}: {}", entity.getId(), e.getMessage());
            return RagRetriever.DISABLED;
        }
    }

    private String decryptOrPlaceholder(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return PLACEHOLDER_API_KEY;
        }
        return encryptionService.decrypt(ciphertext);
    }

    private String decryptOrNull(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(ciphertext);
    }
}
