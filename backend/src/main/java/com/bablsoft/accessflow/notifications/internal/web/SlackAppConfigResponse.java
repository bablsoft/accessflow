package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.api.SlackAppConfigView;

import java.time.Instant;
import java.util.UUID;

/** Admin-facing read of the Slack app configuration. Secrets are never returned — only set flags. */
public record SlackAppConfigResponse(
        UUID id,
        UUID organizationId,
        String appId,
        String defaultChannelId,
        boolean active,
        boolean hasBotToken,
        boolean hasSigningSecret,
        Instant createdAt,
        Instant updatedAt) {

    public static SlackAppConfigResponse from(SlackAppConfigView v) {
        return new SlackAppConfigResponse(
                v.id(), v.organizationId(), v.appId(), v.defaultChannelId(), v.active(),
                v.hasBotToken(), v.hasSigningSecret(), v.createdAt(), v.updatedAt());
    }
}
