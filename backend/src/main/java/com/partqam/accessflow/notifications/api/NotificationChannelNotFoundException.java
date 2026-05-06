package com.partqam.accessflow.notifications.api;

import java.util.UUID;

public class NotificationChannelNotFoundException extends RuntimeException {

    private final UUID channelId;

    public NotificationChannelNotFoundException(UUID channelId) {
        super("Notification channel not found: " + channelId);
        this.channelId = channelId;
    }

    public UUID channelId() {
        return channelId;
    }
}
