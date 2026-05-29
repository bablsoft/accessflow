package com.bablsoft.accessflow.notifications.api;

/**
 * Thrown when a Slack app upsert is missing required fields — e.g. creating a configuration
 * without a bot token or signing secret. Maps to HTTP 422.
 */
public final class SlackAppConfigValidationException extends RuntimeException {

    public SlackAppConfigValidationException(String message) {
        super(message);
    }
}
