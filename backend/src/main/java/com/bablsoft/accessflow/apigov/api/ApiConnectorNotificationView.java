package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/** Minimal projection the notifications module needs to render a connector-scoped notification. */
public record ApiConnectorNotificationView(
        UUID id,
        UUID organizationId,
        String name) {
}
