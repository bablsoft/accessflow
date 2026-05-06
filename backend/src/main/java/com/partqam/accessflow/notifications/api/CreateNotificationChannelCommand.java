package com.partqam.accessflow.notifications.api;

import java.util.Map;
import java.util.UUID;

/**
 * Command for creating a new notification channel. {@code config} carries the raw input
 * including unencrypted sensitive fields ({@code smtp_password}, {@code secret}); the
 * service encrypts and renames them before persistence.
 */
public record CreateNotificationChannelCommand(
        UUID organizationId,
        NotificationChannelType channelType,
        String name,
        Map<String, Object> config) {
}
