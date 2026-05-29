package com.bablsoft.accessflow.notifications.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Slack interactive-components tunables: TTL of the one-time account-link code (stored in Redis)
 * and the acceptance window for the {@code X-Slack-Request-Timestamp} HMAC signature — which also
 * doubles as the replay-dedup window.
 */
@ConfigurationProperties("accessflow.notifications.slack")
public record SlackProperties(Duration linkCodeTtl, Duration signatureTolerance) {

    public SlackProperties {
        if (linkCodeTtl == null) {
            linkCodeTtl = Duration.ofMinutes(10);
        }
        if (signatureTolerance == null) {
            signatureTolerance = Duration.ofMinutes(5);
        }
    }
}
