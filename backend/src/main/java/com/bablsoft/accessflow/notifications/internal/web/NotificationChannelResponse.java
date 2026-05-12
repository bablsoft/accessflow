package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationChannelView;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

record NotificationChannelResponse(
        UUID id,
        UUID organizationId,
        NotificationChannelType channelType,
        String name,
        Map<String, Object> config,
        boolean active,
        Instant createdAt) {

    static NotificationChannelResponse from(NotificationChannelView view) {
        return new NotificationChannelResponse(
                view.id(),
                view.organizationId(),
                view.channelType(),
                view.name(),
                view.config(),
                view.active(),
                view.createdAt());
    }
}
