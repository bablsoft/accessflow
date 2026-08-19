package com.bablsoft.accessflow.compliance.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for compliance reporting (#459) and result-export governance (#626).
 *
 * @param maxReportPeriod largest reporting window a single report may span (default one year)
 * @param maxRows         hard cap on rows scanned/returned by a single report or export (default 50,000)
 * @param resultExportMaxRows    hard cap on rows in a result-set CSV export (default 50,000)
 * @param resultExportMaxPdfRows hard cap on rows in a result-set PDF export (default 5,000 —
 *                               PDF table rendering of huge result sets is expensive)
 */
@ConfigurationProperties(prefix = "accessflow.compliance")
public record ComplianceProperties(Duration maxReportPeriod, int maxRows, int resultExportMaxRows,
                                   int resultExportMaxPdfRows) {

    public ComplianceProperties {
        if (maxReportPeriod == null || maxReportPeriod.isZero() || maxReportPeriod.isNegative()) {
            maxReportPeriod = Duration.ofDays(366);
        }
        if (maxRows <= 0) {
            maxRows = 50_000;
        }
        if (resultExportMaxRows <= 0) {
            resultExportMaxRows = 50_000;
        }
        if (resultExportMaxPdfRows <= 0) {
            resultExportMaxPdfRows = 5_000;
        }
    }
}
