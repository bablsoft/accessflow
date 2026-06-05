package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;

/**
 * Mutable AI-config fields. {@code apiKey} semantics:
 * <ul>
 *     <li>{@code null} — leave the existing ciphertext unchanged.</li>
 *     <li>literal {@code "********"} — leave the existing ciphertext unchanged.</li>
 *     <li>blank string — clear the stored key.</li>
 *     <li>any other value — encrypt and persist.</li>
 * </ul>
 * The {@code ragApiKey} and {@code embeddingApiKey} fields follow the same masking semantics.
 *
 * <p>{@code systemPromptTemplate} semantics: {@code null} leaves the stored template unchanged; a
 * blank string resets it to the built-in default; any other value is persisted (and must contain
 * the {@code {{sql}}} placeholder).
 *
 * <p>{@code langfusePromptName} / {@code langfusePromptLabel} semantics: {@code null} leaves the
 * stored value unchanged; a blank string clears it; any other value is persisted. When a prompt
 * name is set the analyzer fetches its system prompt from Langfuse (see {@code langfuse_config}).
 *
 * <p>The {@code rag*} / {@code embedding*} fields configure the optional RAG knowledge base
 * (AF-336); {@code null} leaves the stored value unchanged. They are validated when the resulting
 * row has {@code ragEnabled = true}.
 */
public record UpdateAiConfigCommand(
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

    public static final String MASKED_API_KEY = "********";

    /** Convenience constructor for callers that do not change RAG settings (bootstrap, tests). */
    public UpdateAiConfigCommand(String name, AiProviderType provider, String model, String endpoint,
            String apiKey, Integer timeoutMs, Integer maxPromptTokens, Integer maxCompletionTokens,
            String systemPromptTemplate, String langfusePromptName, String langfusePromptLabel) {
        this(name, provider, model, endpoint, apiKey, timeoutMs, maxPromptTokens, maxCompletionTokens,
                systemPromptTemplate, langfusePromptName, langfusePromptLabel,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
