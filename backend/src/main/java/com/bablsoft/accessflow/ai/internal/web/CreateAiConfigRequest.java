package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.CreateAiConfigCommand;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record CreateAiConfigRequest(
        @NotBlank(message = "{validation.ai_config.name.required}")
        @Size(min = 1, max = 255, message = "{validation.ai_config.name.size}") String name,
        @NotNull(message = "{validation.ai_config.provider.required}") AiProviderType provider,
        @NotBlank(message = "{validation.ai_config.model.required}")
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
        @Size(max = 4096, message = "{validation.ai_config.embedding_api_key.max}") String embeddingApiKey) {

    /** Convenience constructor for callers that do not configure RAG (tests). */
    CreateAiConfigRequest(String name, AiProviderType provider, String model, String endpoint,
            String apiKey, Integer timeoutMs, Integer maxPromptTokens, Integer maxCompletionTokens,
            String systemPromptTemplate, String langfusePromptName, String langfusePromptLabel) {
        this(name, provider, model, endpoint, apiKey, timeoutMs, maxPromptTokens, maxCompletionTokens,
                systemPromptTemplate, langfusePromptName, langfusePromptLabel,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    CreateAiConfigCommand toCommand() {
        return new CreateAiConfigCommand(
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
                embeddingApiKey);
    }
}
