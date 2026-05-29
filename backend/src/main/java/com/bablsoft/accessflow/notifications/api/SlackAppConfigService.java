package com.bablsoft.accessflow.notifications.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Per-organization Slack app configuration (bot token, signing secret, app id, default channel).
 * Secrets are encrypted at rest and never returned; views expose only whether they are set.
 */
public interface SlackAppConfigService {

    Optional<SlackAppConfigView> get(UUID organizationId);

    SlackAppConfigView upsert(UUID organizationId, UpsertSlackAppConfigCommand command);

    void delete(UUID organizationId);
}
