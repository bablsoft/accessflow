package com.bablsoft.accessflow.notifications.internal.codec;

import java.net.URI;

public record MsTeamsChannelConfig(
        URI webhookUrl) {
}
