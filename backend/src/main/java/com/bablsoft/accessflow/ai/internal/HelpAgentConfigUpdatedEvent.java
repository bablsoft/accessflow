package com.bablsoft.accessflow.ai.internal;

import java.util.UUID;

/**
 * Published after {@code DefaultHelpAgentConfigService.update(...)} commits a change to an
 * organization's {@code help_agent_config} row. The corpus indexer consumes it so a newly enabled or
 * re-bound agent is ingested without waiting for the next scheduled pass.
 *
 * <p>Internal to the AI module — {@code public} only because the help implementation lives in the
 * {@code ai.internal.help} sub-package (see the module note in {@code docs/05-backend.md}).
 */
public record HelpAgentConfigUpdatedEvent(
        UUID organizationId,
        UUID helpAgentConfigId,
        boolean enabled,
        UUID aiConfigId,
        boolean retrievalEnabled,
        boolean bindingChanged) {
}
