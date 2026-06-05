package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;

import java.time.Instant;
import java.util.UUID;

/**
 * Read DTO for {@link AiConfigService}. The {@code apiKeyMasked} / {@code ragApiKeyMasked} /
 * {@code embeddingApiKeyMasked} flags are {@code true} whenever a non-empty key is stored — the keys
 * themselves are never returned to callers.
 */
public record AiConfigView(
        UUID id,
        UUID organizationId,
        String name,
        AiProviderType provider,
        String model,
        String endpoint,
        boolean apiKeyMasked,
        int timeoutMs,
        int maxPromptTokens,
        int maxCompletionTokens,
        String systemPromptTemplate,
        String langfusePromptName,
        String langfusePromptLabel,
        // --- RAG knowledge base (AF-336) ---
        boolean ragEnabled,
        RagStoreType ragStoreType,
        int ragTopK,
        double ragSimilarityThreshold,
        String ragEndpoint,
        String ragCollection,
        boolean ragApiKeyMasked,
        AiProviderType embeddingProvider,
        String embeddingModel,
        String embeddingEndpoint,
        boolean embeddingApiKeyMasked,
        int inUseCount,
        Instant createdAt,
        Instant updatedAt) {
}
