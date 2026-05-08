package com.partqam.accessflow.notifications.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module read of a {@code user_notifications} row. The {@code payload} is the raw JSON
 * string persisted alongside the notification — callers parse it as needed.
 */
public record UserNotificationView(
        UUID id,
        UUID userId,
        UUID organizationId,
        NotificationEventType eventType,
        UUID queryRequestId,
        String payloadJson,
        boolean read,
        Instant createdAt,
        Instant readAt) {
}
