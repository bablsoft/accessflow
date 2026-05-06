package com.partqam.accessflow.notifications.internal.web;

import com.partqam.accessflow.notifications.api.NotificationChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

record CreateNotificationChannelRequest(
        @NotBlank String name,
        @NotNull NotificationChannelType channelType,
        @NotNull Map<String, Object> config) {
}
