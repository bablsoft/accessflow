package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * Mutable help-agent settings (AF-901). Each field is applied independently; {@code null} leaves the
 * stored value unchanged (partial update), which is why every field is boxed.
 *
 * <p>{@code aiConfigId} is the one exception to "null means unchanged" being enough: to <em>clear</em>
 * the binding, send {@code clearAiConfig = true} — a JSON {@code null} on a partial update cannot
 * otherwise be told apart from an absent field.
 */
public record UpdateHelpAgentConfigCommand(
        Boolean enabled,
        UUID aiConfigId,
        boolean clearAiConfig,
        Boolean retrievalEnabled,
        Integer topK,
        Double similarityThreshold,
        Integer maxHistoryTurns,
        Integer maxQuestionChars,
        Boolean sendUserContext,
        Integer retentionDays,
        Integer perUserRequestsPerMinute) {
}
