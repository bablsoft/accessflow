package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;

/**
 * Result of a natural-language → API-call generation (text-to-API, AF-500). {@code draft} is the
 * suggested call the user reviews and submits through the normal API-governance pipeline (it is never
 * executed here) — typically a JSON body, request path, or GraphQL document depending on the
 * connector protocol. Provider/model/token fields mirror {@link AiAnalysisResult} for observability.
 */
public record GeneratedApiCall(
        String draft,
        AiProviderType aiProvider,
        String aiModel,
        int promptTokens,
        int completionTokens) {
}
