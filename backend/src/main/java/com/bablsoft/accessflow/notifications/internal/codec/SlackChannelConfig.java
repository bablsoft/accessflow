package com.bablsoft.accessflow.notifications.internal.codec;

import java.net.URI;
import java.util.List;

public record SlackChannelConfig(
        URI webhookUrl,
        String channel,
        List<String> mentionUsers) {
}
