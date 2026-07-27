package com.bablsoft.accessflow.discovery.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the sensitive-data discovery module (AF-623).
 *
 * <ul>
 *   <li>{@code scanPollInterval} — cadence of {@code DiscoveryScanJob}. Read directly by the
 *       {@code @Scheduled} placeholder, listed here for documentation only.</li>
 *   <li>{@code scanTimeBudget} — wall-clock budget for a single datasource scan; tables past the
 *       deadline are skipped and the run is flagged partial.</li>
 *   <li>{@code sampleStatementTimeout} — per-table statement timeout for the bounded sample read
 *       (tighter than the general execution timeout, same idea as the AF-624 estimate bound).</li>
 *   <li>{@code maxTablesPerScan} — hard cap on tables sampled in one scan.</li>
 *   <li>{@code maxAiTablesPerScan} — hard cap on tables sent through the optional AI pass,
 *       bounding provider spend.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.discovery")
public record DiscoveryProperties(Duration scanPollInterval, Duration scanTimeBudget,
                                  Duration sampleStatementTimeout, Integer maxTablesPerScan,
                                  Integer maxAiTablesPerScan) {

    public DiscoveryProperties {
        if (scanPollInterval == null) {
            scanPollInterval = Duration.ofMinutes(15);
        }
        if (scanTimeBudget == null || scanTimeBudget.isNegative() || scanTimeBudget.isZero()) {
            scanTimeBudget = Duration.ofMinutes(10);
        }
        if (sampleStatementTimeout == null || sampleStatementTimeout.isNegative()
                || sampleStatementTimeout.isZero()) {
            sampleStatementTimeout = Duration.ofSeconds(10);
        }
        if (maxTablesPerScan == null || maxTablesPerScan <= 0) {
            maxTablesPerScan = 200;
        }
        if (maxAiTablesPerScan == null || maxAiTablesPerScan < 0) {
            maxAiTablesPerScan = 25;
        }
    }
}
