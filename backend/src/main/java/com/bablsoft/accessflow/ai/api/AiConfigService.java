package com.bablsoft.accessflow.ai.api;

import java.util.List;
import java.util.UUID;

/**
 * Org-scoped admin-tunable AI provider configurations. Each org can have many — datasources bind
 * to a specific row via {@code datasources.ai_config_id}. API-key masking semantics are described
 * in {@link UpdateAiConfigCommand}.
 */
public interface AiConfigService {

    List<AiConfigView> list(UUID organizationId);

    AiConfigView get(UUID id, UUID organizationId);

    AiConfigView create(UUID organizationId, CreateAiConfigCommand command);

    AiConfigView update(UUID id, UUID organizationId, UpdateAiConfigCommand command);

    void delete(UUID id, UUID organizationId);
}
