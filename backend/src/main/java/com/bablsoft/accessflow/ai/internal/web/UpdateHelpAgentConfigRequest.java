package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.UpdateHelpAgentConfigCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.UUID;

/**
 * Partial update of the org's help-agent settings — every field is optional and {@code null} leaves
 * the stored value unchanged. {@code clearAiConfig} exists because a JSON {@code null} on a partial
 * update cannot otherwise be told apart from an absent {@code ai_config_id}.
 *
 * <p>The bounds mirror {@code DefaultHelpAgentConfigService}'s range validation exactly; the service
 * re-checks them because it is also reachable with a stored value the request did not carry.
 */
record UpdateHelpAgentConfigRequest(
        Boolean enabled,
        UUID aiConfigId,
        Boolean clearAiConfig,
        Boolean retrievalEnabled,
        @Min(value = 1, message = "{validation.help_agent.top_k.range}")
        @Max(value = 20, message = "{validation.help_agent.top_k.range}")
        Integer topK,
        @DecimalMin(value = "0.0", message = "{validation.help_agent.similarity_threshold.range}")
        @DecimalMax(value = "1.0", message = "{validation.help_agent.similarity_threshold.range}")
        Double similarityThreshold,
        @Min(value = 1, message = "{validation.help_agent.max_history_turns.range}")
        @Max(value = 50, message = "{validation.help_agent.max_history_turns.range}")
        Integer maxHistoryTurns,
        @Min(value = 100, message = "{validation.help_agent.max_question_chars.range}")
        @Max(value = 10000, message = "{validation.help_agent.max_question_chars.range}")
        Integer maxQuestionChars,
        Boolean sendUserContext,
        @Min(value = 1, message = "{validation.help_agent.retention_days.range}")
        @Max(value = 3650, message = "{validation.help_agent.retention_days.range}")
        Integer retentionDays,
        @Min(value = 1, message = "{validation.help_agent.requests_per_minute.range}")
        @Max(value = 120, message = "{validation.help_agent.requests_per_minute.range}")
        Integer perUserRequestsPerMinute) {

    UpdateHelpAgentConfigCommand toCommand() {
        return new UpdateHelpAgentConfigCommand(
                enabled,
                aiConfigId,
                Boolean.TRUE.equals(clearAiConfig),
                retrievalEnabled,
                topK,
                similarityThreshold,
                maxHistoryTurns,
                maxQuestionChars,
                sendUserContext,
                retentionDays,
                perUserRequestsPerMinute);
    }
}
