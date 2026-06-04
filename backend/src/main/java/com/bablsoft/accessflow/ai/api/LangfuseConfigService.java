package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * Manages the per-organization Langfuse integration settings (a singleton row per org). Exposed for
 * cross-module use (the bootstrap reconciler), mirroring {@code SamlConfigService}.
 */
public interface LangfuseConfigService {

    /**
     * The persisted configuration for the organization, or a transient all-default view (disabled,
     * no credentials) when none exists yet. The secret key is never included.
     */
    LangfuseConfigView getOrDefault(UUID organizationId);

    /** Upsert the configuration for the organization, applying the partial-update semantics of {@link UpdateLangfuseConfigCommand}. */
    LangfuseConfigView update(UUID organizationId, UpdateLangfuseConfigCommand command);

    /** Verifies the org's saved Langfuse credentials by calling an authenticated Langfuse endpoint. */
    LangfuseConnectionTestResult testConnection(UUID organizationId);
}
