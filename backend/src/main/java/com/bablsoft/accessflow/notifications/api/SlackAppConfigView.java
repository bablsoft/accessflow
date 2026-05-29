package com.bablsoft.accessflow.notifications.api;

import java.time.Instant;
import java.util.UUID;

/**
 * API-safe read of a {@code slack_app_config} row. The bot token and signing secret are never
 * exposed — only whether they are set ({@code hasBotToken} / {@code hasSigningSecret}).
 */
public record SlackAppConfigView(
        UUID id,
        UUID organizationId,
        String appId,
        String defaultChannelId,
        boolean active,
        boolean hasBotToken,
        boolean hasSigningSecret,
        Instant createdAt,
        Instant updatedAt) {
}
