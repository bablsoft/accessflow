package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;

import java.util.UUID;

/**
 * Read DTO for one orchestration member of an {@link AiConfigView} (AF-450). {@code apiKeyMasked} is
 * {@code true} whenever a non-empty key is stored — the key itself is never returned.
 */
public record AiConfigModelView(
        UUID id,
        AiProviderType provider,
        String model,
        String endpoint,
        boolean apiKeyMasked,
        double weight,
        boolean enabled) {
}
