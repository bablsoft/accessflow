package com.partqam.accessflow.notifications.events;

import java.util.UUID;

/**
 * Published after a {@code user_notifications} row has been persisted. The realtime module
 * consumes this and pushes a {@code notification.created} envelope to the recipient's open
 * WebSocket sessions. Workflow correctness is unaffected if no realtime listener fires.
 */
public record UserNotificationCreatedEvent(UUID notificationId, UUID userId) {
}
