package com.bablsoft.accessflow.notifications.internal.codec;

import java.net.URI;

public record DiscordChannelConfig(
        URI webhookUrl,
        String username,
        String avatarUrl) {
}
