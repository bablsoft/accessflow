package com.bablsoft.accessflow.notifications.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module lookup for in-app user notifications. Used by the realtime module to fan a freshly
 * persisted notification out to the recipient's WebSocket sessions without reaching into
 * {@code notifications/internal}.
 */
public interface UserNotificationLookupService {

    Optional<UserNotificationView> findById(UUID notificationId);
}
