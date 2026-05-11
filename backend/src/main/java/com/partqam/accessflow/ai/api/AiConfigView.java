package com.partqam.accessflow.ai.api;

import com.partqam.accessflow.core.api.AiProviderType;

import java.time.Instant;
import java.util.UUID;

/**
 * Read DTO for {@link AiConfigService}. The {@code apiKeyMasked} flag is {@code true} whenever a
 * non-empty API key is stored — the field itself is never returned to callers.
 */
public record AiConfigView(
        UUID id,
        UUID organizationId,
        String name,
        AiProviderType provider,
        String model,
        String endpoint,
        boolean apiKeyMasked,
        int timeoutMs,
        int maxPromptTokens,
        int maxCompletionTokens,
        int inUseCount,
        Instant createdAt,
        Instant updatedAt) {
}
