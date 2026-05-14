package com.bablsoft.accessflow.bootstrap.internal.spec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;

import java.util.LinkedHashMap;
import java.util.Map;

public record NotificationChannelSpec(
        String name,
        NotificationChannelType channelType,
        Boolean active,
        Map<String, Object> config
) {

    public NotificationChannelSpec {
        config = config == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(config));
    }
}
