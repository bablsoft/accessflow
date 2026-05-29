package com.bablsoft.accessflow.notifications.api;

/**
 * Create-or-update the Slack app configuration for an organization. {@code botToken} and
 * {@code signingSecret} are write-only: passing {@link #MASKED} (or {@code null}) keeps the
 * existing encrypted value; passing a fresh value re-encrypts it. Both are required when no
 * configuration exists yet.
 */
public record UpsertSlackAppConfigCommand(
        String appId,
        String defaultChannelId,
        String botToken,
        String signingSecret,
        Boolean active) {

    /** Placeholder the API echoes back for set secrets; submitting it leaves the secret unchanged. */
    public static final String MASKED = "********";
}
