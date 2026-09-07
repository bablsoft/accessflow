package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.AiProviderType;

import java.util.UUID;

/**
 * Published after {@code DefaultAiConfigService.update(...)} commits a change to an
 * {@code ai_config} row. Consumed by {@link AiAnalyzerStrategyHolder} to evict the cached
 * delegate for that row so the next {@code analyze(...)} call rebuilds against the new row.
 *
 * <p>Internal to the AI module — no other module consumes it. {@code public} only because the help
 * corpus indexer lives in the {@code ai.internal.help} sub-package, which a package-private record
 * cannot reach; a sub-package gets no package-private access (see the module note in
 * {@code docs/05-backend.md}). It re-indexes on {@code ragChanged()}, since swapping the embedding
 * model or the vector store invalidates every stored help vector.
 */
public record AiConfigUpdatedEvent(
        UUID aiConfigId,
        AiProviderType oldProvider,
        AiProviderType newProvider,
        String oldModel,
        String newModel,
        boolean apiKeyChanged,
        boolean promptChanged,
        boolean ragChanged,
        boolean orchestrationChanged) {
}
