package com.bablsoft.accessflow.notifications.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Ticketing inbound-webhook tunables (AF-453): the acceptance window for the
 * {@code X-AccessFlow-Timestamp} HMAC signature on ServiceNow / Jira status callbacks — which also
 * doubles as the replay-dedup window.
 */
@ConfigurationProperties("accessflow.notifications.ticketing")
public record TicketingProperties(Duration signatureTolerance) {

    public TicketingProperties {
        if (signatureTolerance == null) {
            signatureTolerance = Duration.ofMinutes(5);
        }
    }
}
