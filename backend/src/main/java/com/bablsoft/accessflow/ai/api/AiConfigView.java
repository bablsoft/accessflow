package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;
import com.bablsoft.accessflow.core.api.VotingStrategy;

import java.time.Instant;
import java.util.List;
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
        // --- Multi-model orchestration + guardrails (AF-450) ---
        boolean orchestrationEnabled,
        VotingStrategy votingStrategy,
        double votingWeight,
        List<String> guardrailPatterns,
        List<AiConfigModelView> models,
        // --- Provider fallback pool (AF-458): null = not a fallback; lower = tried first ---
        Integer fallbackPriority,
        int inUseCount,
        Instant createdAt,
        Instant updatedAt) {

    public AiConfigView {
        guardrailPatterns = guardrailPatterns == null ? List.of() : List.copyOf(guardrailPatterns);
        models = models == null ? List.of() : List.copyOf(models);
    }
}
