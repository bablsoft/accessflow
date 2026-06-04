package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;

/**
 * Mutable AI-config fields. {@code apiKey} semantics:
 * <ul>
 *     <li>{@code null} — leave the existing ciphertext unchanged.</li>
 *     <li>literal {@code "********"} — leave the existing ciphertext unchanged.</li>
 *     <li>blank string — clear the stored key.</li>
 *     <li>any other value — encrypt and persist.</li>
 * </ul>
 *
 * <p>{@code systemPromptTemplate} semantics: {@code null} leaves the stored template unchanged; a
 * blank string resets it to the built-in default; any other value is persisted (and must contain
 * the {@code {{sql}}} placeholder).
 *
 * <p>{@code langfusePromptName} / {@code langfusePromptLabel} semantics: {@code null} leaves the
 * stored value unchanged; a blank string clears it; any other value is persisted. When a prompt
 * name is set the analyzer fetches its system prompt from Langfuse (see {@code langfuse_config}).
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
        String langfusePromptLabel) {

    public static final String MASKED_API_KEY = "********";
}
