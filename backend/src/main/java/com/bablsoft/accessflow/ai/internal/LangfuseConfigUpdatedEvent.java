package com.bablsoft.accessflow.ai.internal;

import java.util.UUID;

/**
 * Published after a {@code langfuse_config} row is upserted. The resolver and prompt-fetch caches
 * listen for it and evict the affected organization so the next trace / prompt fetch reflects the
 * new credentials and toggles — no restart needed.
 */
record LangfuseConfigUpdatedEvent(UUID organizationId) {
}
