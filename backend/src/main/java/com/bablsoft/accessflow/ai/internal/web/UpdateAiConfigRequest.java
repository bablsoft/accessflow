package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;
import com.bablsoft.accessflow.core.api.VotingStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

record UpdateAiConfigRequest(
        @Size(min = 1, max = 255, message = "{validation.ai_config.name.size}") String name,
        AiProviderType provider,
        @Size(max = 100, message = "{validation.ai_config.model.max}") String model,
        @Size(max = 500, message = "{validation.ai_config.endpoint.max}") String endpoint,
        @Size(max = 4096, message = "{validation.ai_config.api_key.max}") String apiKey,
        @Min(value = 1000, message = "{validation.ai_config.timeout_ms.range}")
        @Max(value = 600000, message = "{validation.ai_config.timeout_ms.range}") Integer timeoutMs,
        @Min(value = 100, message = "{validation.ai_config.max_prompt_tokens.range}")
        @Max(value = 200000, message = "{validation.ai_config.max_prompt_tokens.range}") Integer maxPromptTokens,
        @Min(value = 100, message = "{validation.ai_config.max_completion_tokens.range}")
        @Max(value = 200000, message = "{validation.ai_config.max_completion_tokens.range}") Integer maxCompletionTokens,
        @Size(max = 20000, message = "{validation.ai_config.system_prompt.max}") String systemPromptTemplate,
        @Size(max = 255, message = "{validation.ai_config.langfuse_prompt_name.max}") String langfusePromptName,
        @Size(max = 255, message = "{validation.ai_config.langfuse_prompt_label.max}") String langfusePromptLabel,
        Boolean ragEnabled,
        RagStoreType ragStoreType,
        @Min(value = 1, message = "{validation.ai_config.rag_top_k.range}")
        @Max(value = 20, message = "{validation.ai_config.rag_top_k.range}") Integer ragTopK,
        @DecimalMin(value = "0.0", message = "{validation.ai_config.rag_similarity_threshold.range}")
        @DecimalMax(value = "1.0", message = "{validation.ai_config.rag_similarity_threshold.range}") Double ragSimilarityThreshold,
        @Size(max = 500, message = "{validation.ai_config.rag_endpoint.max}") String ragEndpoint,
        @Size(max = 255, message = "{validation.ai_config.rag_collection.max}") String ragCollection,
        @Size(max = 4096, message = "{validation.ai_config.rag_api_key.max}") String ragApiKey,
        AiProviderType embeddingProvider,
        @Size(max = 100, message = "{validation.ai_config.embedding_model.max}") String embeddingModel,
        @Size(max = 500, message = "{validation.ai_config.embedding_endpoint.max}") String embeddingEndpoint,
        @Size(max = 4096, message = "{validation.ai_config.embedding_api_key.max}") String embeddingApiKey,
        Boolean orchestrationEnabled,
        VotingStrategy votingStrategy,
        @DecimalMin(value = "0.0", inclusive = false,
                message = "{validation.ai_config.voting_weight.range}") Double votingWeight,
        @Size(max = 50, message = "{validation.ai_config.guardrail_patterns.count}")
        List<@Size(max = 500, message = "{validation.ai_config.guardrail_pattern.max}") String> guardrailPatterns,
        @Size(max = 20, message = "{validation.ai_config.models.count}")
        List<@Valid AiConfigModelRequest> models,
        // -1 clears the stored priority (the config stops being a fallback); null leaves it unchanged.
        @Min(value = -1, message = "{validation.ai_config.fallback_priority.range}")
        @Max(value = 100, message = "{validation.ai_config.fallback_priority.range}") Integer fallbackPriority) {

    /** Convenience constructor for callers that do not change RAG settings (tests). */
    UpdateAiConfigRequest(String name, AiProviderType provider, String model, String endpoint,
            String apiKey, Integer timeoutMs, Integer maxPromptTokens, Integer maxCompletionTokens,
            String systemPromptTemplate, String langfusePromptName, String langfusePromptLabel) {
        this(name, provider, model, endpoint, apiKey, timeoutMs, maxPromptTokens, maxCompletionTokens,
                systemPromptTemplate, langfusePromptName, langfusePromptLabel,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    UpdateAiConfigCommand toCommand() {
        return new UpdateAiConfigCommand(
                name,
                provider,
                model,
                endpoint,
                apiKey,
                timeoutMs,
                maxPromptTokens,
                maxCompletionTokens,
                systemPromptTemplate,
                langfusePromptName,
                langfusePromptLabel,
                ragEnabled,
                ragStoreType,
                ragTopK,
                ragSimilarityThreshold,
                ragEndpoint,
                ragCollection,
                ragApiKey,
                embeddingProvider,
                embeddingModel,
                embeddingEndpoint,
                embeddingApiKey,
                orchestrationEnabled,
                votingStrategy,
                votingWeight,
                guardrailPatterns,
                models == null ? null : models.stream().map(AiConfigModelRequest::toCommand).toList(),
                fallbackPriority);
    }
}
