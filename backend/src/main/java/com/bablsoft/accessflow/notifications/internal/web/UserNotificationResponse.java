package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.api.UserNotificationView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.UUID;

public record UserNotificationResponse(
        UUID id,
        NotificationEventType eventType,
        UUID queryRequestId,
        JsonNode payload,
        boolean read,
        Instant createdAt,
        Instant readAt) {

    public static UserNotificationResponse from(UserNotificationView view, ObjectMapper mapper) {
        JsonNode payload;
        try {
            payload = view.payloadJson() == null || view.payloadJson().isBlank()
                    ? JsonNodeFactory.instance.objectNode()
                    : mapper.readTree(view.payloadJson());
        } catch (RuntimeException ex) {
            payload = JsonNodeFactory.instance.objectNode();
        }
        return new UserNotificationResponse(
                view.id(),
                view.eventType(),
                view.queryRequestId(),
                payload,
                view.read(),
                view.createdAt(),
                view.readAt());
    }
}
