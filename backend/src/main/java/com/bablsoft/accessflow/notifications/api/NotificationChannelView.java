package com.bablsoft.accessflow.notifications.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-module read of a {@code notification_channels} row. Sensitive config fields
 * (SMTP password, webhook secret) are replaced with the masked placeholder
 * {@value DefaultMaskedPlaceholder#VALUE} so this view is safe to return from APIs.
 */
public record NotificationChannelView(
        UUID id,
        UUID organizationId,
        NotificationChannelType channelType,
        String name,
        Map<String, Object> config,
        boolean active,
        Instant createdAt) {

    public static final class DefaultMaskedPlaceholder {
        public static final String VALUE = "********";

        private DefaultMaskedPlaceholder() {
        }
    }
}
