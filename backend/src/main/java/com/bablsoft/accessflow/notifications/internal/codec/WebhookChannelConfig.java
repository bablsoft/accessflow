package com.bablsoft.accessflow.notifications.internal.codec;

import java.net.URI;

public record WebhookChannelConfig(
        URI url,
        String secretPlain,
        int timeoutSeconds) {
}
