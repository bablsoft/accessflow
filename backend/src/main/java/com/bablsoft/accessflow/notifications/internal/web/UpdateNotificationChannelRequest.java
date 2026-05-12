package com.bablsoft.accessflow.notifications.internal.web;

import java.util.Map;

record UpdateNotificationChannelRequest(
        String name,
        Map<String, Object> config,
        Boolean active) {
}
