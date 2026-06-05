package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;

/**
 * Result of a natural-language → SQL generation. {@code sql} is a draft statement the caller pastes
 * into the editor and submits through the normal query pipeline; it is never executed here. The
 * provider / model / token fields mirror {@link AiAnalysisResult} for observability.
 */
public record GeneratedSqlResult(
        String sql,
        AiProviderType aiProvider,
        String aiModel,
        int promptTokens,
        int completionTokens) {
}
