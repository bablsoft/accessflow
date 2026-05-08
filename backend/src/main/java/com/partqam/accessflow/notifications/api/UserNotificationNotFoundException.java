package com.partqam.accessflow.notifications.api;

import java.util.UUID;

public class UserNotificationNotFoundException extends RuntimeException {

    private final UUID notificationId;

    public UserNotificationNotFoundException(UUID notificationId) {
        super("User notification not found: " + notificationId);
        this.notificationId = notificationId;
    }

    public UUID notificationId() {
        return notificationId;
    }
}
