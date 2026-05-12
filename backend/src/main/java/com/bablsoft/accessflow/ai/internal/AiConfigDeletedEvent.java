package com.bablsoft.accessflow.ai.internal;

import java.util.UUID;

/**
 * Published after {@code DefaultAiConfigService.delete(...)} commits removal of an
 * {@code ai_config} row. Consumed by {@link AiAnalyzerStrategyHolder} to evict the cached
 * delegate so memory and HikariCP-like resources are released promptly.
 *
 * <p>Internal to the AI module — no other module consumes it.
 */
record AiConfigDeletedEvent(UUID aiConfigId) {
}
