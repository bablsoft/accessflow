package com.bablsoft.accessflow.core.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the data-budget usage ledger (#942).
 *
 * @param usageRetention how long ledger rows are kept. Must outlive the longest budget window
 *                       (31 days), or a budget would under-count — shorter values are raised to
 *                       the floor.
 */
@ConfigurationProperties("accessflow.core.data-budget")
public record DataBudgetProperties(Duration usageRetention) {

    public static final Duration MIN_RETENTION = Duration.ofDays(31).plusHours(1);
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(32);

    public DataBudgetProperties {
        if (usageRetention == null) {
            usageRetention = DEFAULT_RETENTION;
        } else if (usageRetention.compareTo(MIN_RETENTION) < 0) {
            usageRetention = MIN_RETENTION;
        }
    }
}
