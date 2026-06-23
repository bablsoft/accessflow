package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;
import com.bablsoft.accessflow.core.api.VotingStrategy;

import java.util.List;

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
 *
 * <p>The {@code orchestrationEnabled} / {@code votingStrategy} / {@code votingWeight} /
 * {@code guardrailPatterns} / {@code models} fields configure multi-model orchestration + guardrails
 * (AF-450); {@code null} leaves the stored value unchanged. A non-null {@code models} list
 * <em>replaces</em> the member set (members are matched by id to preserve masked keys).
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
        String embeddingApiKey,
        Boolean orchestrationEnabled,
        VotingStrategy votingStrategy,
        Double votingWeight,
        List<String> guardrailPatterns,
        List<AiConfigModelCommand> models) {

    public static final String MASKED_API_KEY = "********";

    /** Convenience constructor for callers that do not change RAG settings (bootstrap, tests). */
    public UpdateAiConfigCommand(String name, AiProviderType provider, String model, String endpoint,
            String apiKey, Integer timeoutMs, Integer maxPromptTokens, Integer maxCompletionTokens,
            String systemPromptTemplate, String langfusePromptName, String langfusePromptLabel) {
        this(name, provider, model, endpoint, apiKey, timeoutMs, maxPromptTokens, maxCompletionTokens,
                systemPromptTemplate, langfusePromptName, langfusePromptLabel,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Convenience constructor for callers that change RAG but not orchestration (tests). */
    public UpdateAiConfigCommand(String name, AiProviderType provider, String model, String endpoint,
            String apiKey, Integer timeoutMs, Integer maxPromptTokens, Integer maxCompletionTokens,
            String systemPromptTemplate, String langfusePromptName, String langfusePromptLabel,
            Boolean ragEnabled, RagStoreType ragStoreType, Integer ragTopK,
            Double ragSimilarityThreshold, String ragEndpoint, String ragCollection, String ragApiKey,
            AiProviderType embeddingProvider, String embeddingModel, String embeddingEndpoint,
            String embeddingApiKey) {
        this(name, provider, model, endpoint, apiKey, timeoutMs, maxPromptTokens, maxCompletionTokens,
                systemPromptTemplate, langfusePromptName, langfusePromptLabel, ragEnabled, ragStoreType,
                ragTopK, ragSimilarityThreshold, ragEndpoint, ragCollection, ragApiKey, embeddingProvider,
                embeddingModel, embeddingEndpoint, embeddingApiKey, null, null, null, null, null);
    }
}
