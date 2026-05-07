package com.partqam.accessflow.ai.api;

import com.partqam.accessflow.core.api.AiProviderType;

/**
 * Mutable AI-config fields. {@code apiKey} semantics:
 * <ul>
 *     <li>{@code null} — leave the existing ciphertext unchanged.</li>
 *     <li>literal {@code "********"} — leave the existing ciphertext unchanged.</li>
 *     <li>blank string — clear the stored key (so the AI module falls back to the environment).</li>
 *     <li>any other value — encrypt and persist.</li>
 * </ul>
 */
public record UpdateAiConfigCommand(
        AiProviderType provider,
        String model,
        String endpoint,
        String apiKey,
        Integer timeoutMs,
        Integer maxPromptTokens,
        Integer maxCompletionTokens,
        Boolean enableAiDefault,
        Boolean autoApproveLow,
        Boolean blockCritical,
        Boolean includeSchema) {

    public static final String MASKED_API_KEY = "********";
}
