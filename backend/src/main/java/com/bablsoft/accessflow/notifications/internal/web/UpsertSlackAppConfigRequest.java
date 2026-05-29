package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.api.UpsertSlackAppConfigCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create-or-update the Slack app config. {@code botToken}/{@code signingSecret} are write-only:
 * omit or send the masked placeholder to keep the existing value.
 */
public record UpsertSlackAppConfigRequest(
        @NotBlank(message = "{validation.slack_app.app_id.required}")
        @Size(max = 64, message = "{validation.slack_app.app_id.max}")
        String appId,

        @NotBlank(message = "{validation.slack_app.default_channel.required}")
        @Size(max = 64, message = "{validation.slack_app.default_channel.max}")
        String defaultChannelId,

        @Size(max = 512, message = "{validation.slack_app.bot_token.max}")
        String botToken,

        @Size(max = 255, message = "{validation.slack_app.signing_secret.max}")
        String signingSecret,

        Boolean active) {

    public UpsertSlackAppConfigCommand toCommand() {
        return new UpsertSlackAppConfigCommand(appId, defaultChannelId, botToken, signingSecret, active);
    }
}
