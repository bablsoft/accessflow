package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

record CreateNotificationChannelRequest(
        @NotBlank(message = "{validation.notification_name.required}") String name,
        @NotNull(message = "{validation.notification_type.required}") NotificationChannelType channelType,
        @NotNull(message = "{validation.notification_config.required}") Map<String, Object> config) {
}
