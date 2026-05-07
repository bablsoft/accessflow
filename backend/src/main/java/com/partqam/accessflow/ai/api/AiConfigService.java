package com.partqam.accessflow.ai.api;

import java.util.UUID;

/**
 * Org-scoped admin-tunable AI settings. {@link #getOrDefault} returns the persisted row or a
 * transient default snapshot derived from {@code accessflow.ai.provider}; {@link #update} performs
 * an upsert and applies the API-key masking semantics described in {@link UpdateAiConfigCommand}.
 */
public interface AiConfigService {

    AiConfigView getOrDefault(UUID organizationId);

    AiConfigView update(UUID organizationId, UpdateAiConfigCommand command);
}
