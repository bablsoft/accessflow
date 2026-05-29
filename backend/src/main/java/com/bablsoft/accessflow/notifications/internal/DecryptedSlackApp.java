package com.bablsoft.accessflow.notifications.internal;

import java.util.UUID;

/**
 * Runtime view of an active {@code slack_app_config} row with secrets decrypted — used internally
 * by the Slack notification strategy and the inbound interaction handler. Never leaves the module.
 */
public record DecryptedSlackApp(
        UUID organizationId,
        String appId,
        String botToken,
        String signingSecret,
        String defaultChannelId) {
}
