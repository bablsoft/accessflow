package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AiConfigView;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;
import com.bablsoft.accessflow.core.api.VotingStrategy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record AiConfigResponse(
        UUID id,
        UUID organizationId,
        String name,
        AiProviderType provider,
        String model,
        String endpoint,
        String apiKey,
        int timeoutMs,
        int maxPromptTokens,
        int maxCompletionTokens,
        String systemPromptTemplate,
        String langfusePromptName,
        String langfusePromptLabel,
        boolean ragEnabled,
        RagStoreType ragStoreType,
        int ragTopK,
        double ragSimilarityThreshold,
        String ragEndpoint,
        String ragCollection,
        String ragApiKey,
        AiProviderType embeddingProvider,
        String embeddingModel,
        String embeddingEndpoint,
        String embeddingApiKey,
        boolean orchestrationEnabled,
        VotingStrategy votingStrategy,
        double votingWeight,
        List<String> guardrailPatterns,
        List<AiConfigModelResponse> models,
        Integer fallbackPriority,
        int inUseCount,
        Instant createdAt,
        Instant updatedAt) {

    static AiConfigResponse from(AiConfigView view) {
        return new AiConfigResponse(
                view.id(),
                view.organizationId(),
                view.name(),
                view.provider(),
                view.model(),
                view.endpoint(),
                view.apiKeyMasked() ? UpdateAiConfigCommand.MASKED_API_KEY : null,
                view.timeoutMs(),
                view.maxPromptTokens(),
                view.maxCompletionTokens(),
                view.systemPromptTemplate(),
                view.langfusePromptName(),
                view.langfusePromptLabel(),
                view.ragEnabled(),
                view.ragStoreType(),
                view.ragTopK(),
                view.ragSimilarityThreshold(),
                view.ragEndpoint(),
                view.ragCollection(),
                view.ragApiKeyMasked() ? UpdateAiConfigCommand.MASKED_API_KEY : null,
                view.embeddingProvider(),
                view.embeddingModel(),
                view.embeddingEndpoint(),
                view.embeddingApiKeyMasked() ? UpdateAiConfigCommand.MASKED_API_KEY : null,
                view.orchestrationEnabled(),
                view.votingStrategy(),
                view.votingWeight(),
                view.guardrailPatterns(),
                view.models().stream().map(AiConfigModelResponse::from).toList(),
                view.fallbackPriority(),
                view.inUseCount(),
                view.createdAt(),
                view.updatedAt());
    }
}
