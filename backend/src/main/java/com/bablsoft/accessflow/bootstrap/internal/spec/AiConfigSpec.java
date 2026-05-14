package com.bablsoft.accessflow.bootstrap.internal.spec;

import com.bablsoft.accessflow.core.api.AiProviderType;

public record AiConfigSpec(
        String name,
        AiProviderType provider,
        String model,
        String endpoint,
        String apiKey,
        Integer timeoutMs,
        Integer maxPromptTokens,
        Integer maxCompletionTokens
) {
}
