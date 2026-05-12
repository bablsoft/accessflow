package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;

public interface NotificationChannelStrategy {

    NotificationChannelType supports();

    void deliver(NotificationContext ctx, NotificationChannelEntity channel);

    /**
     * Per-channel-type behavior for the {@code POST /admin/notification-channels/{id}/test}
     * admin endpoint. Email honors {@code optionalEmailOverride}; other channel types ignore it.
     */
    void sendTest(NotificationChannelEntity channel, String optionalEmailOverride);
}
