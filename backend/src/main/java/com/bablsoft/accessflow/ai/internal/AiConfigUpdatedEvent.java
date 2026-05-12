package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.AiProviderType;

import java.util.UUID;

/**
 * Published after {@code DefaultAiConfigService.update(...)} commits a change to an
 * {@code ai_config} row. Consumed by {@link AiAnalyzerStrategyHolder} to evict the cached
 * delegate for that row so the next {@code analyze(...)} call rebuilds against the new row.
 *
 * <p>Internal to the AI module — no other module consumes it.
 */
record AiConfigUpdatedEvent(
        UUID aiConfigId,
        AiProviderType oldProvider,
        AiProviderType newProvider,
        String oldModel,
        String newModel,
        boolean apiKeyChanged) {
}
