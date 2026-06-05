package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;

/**
 * Inputs for creating a new {@code ai_config} row. {@code apiKey} may be {@code null}/blank for
 * Ollama (which is keyless). For cloud providers the holder will reject {@code analyze(...)}
 * calls with {@code AiAnalysisException} until an API key is set via a subsequent update.
 *
 * <p>The {@code rag*} / {@code embedding*} fields configure the optional RAG knowledge base
 * (AF-336). They are only required / validated when {@code ragEnabled} is {@code true}.
 */
public record CreateAiConfigCommand(
        String name,
        AiProviderType provider,
        String model,
        String endpoint,
        String apiKey,
        Integer timeoutMs,
        Integer maxPromptTokens,
        Integer maxCompletionTokens,
        String systemPromptTemplate,
        String langfusePromptName,
        String langfusePromptLabel,
        Boolean ragEnabled,
        RagStoreType ragStoreType,
        Integer ragTopK,
        Double ragSimilarityThreshold,
        String ragEndpoint,
        String ragCollection,
        String ragApiKey,
        AiProviderType embeddingProvider,
        String embeddingModel,
        String embeddingEndpoint,
        String embeddingApiKey) {

    /** Convenience constructor for callers that do not configure RAG (bootstrap, tests). */
    public CreateAiConfigCommand(String name, AiProviderType provider, String model, String endpoint,
            String apiKey, Integer timeoutMs, Integer maxPromptTokens, Integer maxCompletionTokens,
            String systemPromptTemplate, String langfusePromptName, String langfusePromptLabel) {
        this(name, provider, model, endpoint, apiKey, timeoutMs, maxPromptTokens, maxCompletionTokens,
                systemPromptTemplate, langfusePromptName, langfusePromptLabel,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
