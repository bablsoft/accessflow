package com.bablsoft.accessflow.notifications.api;

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
        UUID apiRequestId,
        UUID deploymentRequestId,
        UUID schemaChangePromotionId,
        String payloadJson,
        boolean read,
        Instant createdAt,
        Instant readAt) {

    /** Compatibility constructor without the #882 schema-change promotion target. */
    public UserNotificationView(UUID id, UUID userId, UUID organizationId,
                                NotificationEventType eventType, UUID queryRequestId,
                                UUID apiRequestId, UUID deploymentRequestId, String payloadJson,
                                boolean read, Instant createdAt, Instant readAt) {
        this(id, userId, organizationId, eventType, queryRequestId, apiRequestId,
                deploymentRequestId, null, payloadJson, read, createdAt, readAt);
    }
}
