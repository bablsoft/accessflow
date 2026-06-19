package com.bablsoft.accessflow.compliance.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for compliance reporting (#459).
 *
 * @param maxReportPeriod largest reporting window a single report may span (default one year)
 * @param maxRows         hard cap on rows scanned/returned by a single report or export (default 50,000)
 */
@ConfigurationProperties(prefix = "accessflow.compliance")
public record ComplianceProperties(Duration maxReportPeriod, int maxRows) {

    public ComplianceProperties {
        if (maxReportPeriod == null || maxReportPeriod.isZero() || maxReportPeriod.isNegative()) {
            maxReportPeriod = Duration.ofDays(366);
        }
        if (maxRows <= 0) {
            maxRows = 50_000;
        }
    }
}
