package com.bablsoft.accessflow.notifications.api;

import java.util.UUID;

/** Thrown when no {@code slack_app_config} row exists for the organization. */
public final class SlackAppConfigNotFoundException extends RuntimeException {

    private final UUID organizationId;

    public SlackAppConfigNotFoundException(UUID organizationId) {
        super("Slack app configuration not found for organization " + organizationId);
        this.organizationId = organizationId;
    }

    public UUID organizationId() {
        return organizationId;
    }
}
