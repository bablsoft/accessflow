package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;

/**
 * Inputs for creating a new {@code ai_config} row. {@code apiKey} may be {@code null}/blank for
 * Ollama (which is keyless). For cloud providers the holder will reject {@code analyze(...)}
 * calls with {@code AiAnalysisException} until an API key is set via a subsequent update.
 */
public record CreateAiConfigCommand(
        String name,
        AiProviderType provider,
        String model,
        String endpoint,
        String apiKey,
        Integer timeoutMs,
        Integer maxPromptTokens,
        Integer maxCompletionTokens) {
}
